"""Opt-in kafka-python wire contract using a fresh, unmounted local Docker broker."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "build"))
from middleware_images import image

SPEC = importlib.util.spec_from_file_location("pipeline_kafka_fixture", ROOT / "build/verify-pipeline.py")
PIPELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIPELINE)


@unittest.skipUnless(os.environ.get("SOCP_PIPELINE_KAFKA_TESTS") == "true", "opt-in disposable Kafka fixture")
class PipelineKafkaWireTest(unittest.TestCase):
    @classmethod
    def docker(cls, *args, **kwargs):
        return subprocess.run(["docker", *args], check=True, capture_output=True, text=True,
                              timeout=kwargs.get("timeout", 30)).stdout.strip()

    @classmethod
    def setUpClass(cls):
        from kafka import KafkaAdminClient
        from kafka.errors import KafkaError, KafkaConfigurationError
        endpoint = os.environ.get("DOCKER_HOST") or cls.docker("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
        if not endpoint.startswith(("unix://", "npipe://")):
            raise RuntimeError("this fixture requires a local Docker socket or named pipe")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        cls.broker = "127.0.0.1:%d" % port
        name = "socp-audit-pipeline-" + uuid.uuid4().hex
        options = ["run", "--detach", "--rm", "--pull=never", "--name", name,
                   "--label", "socp.audit.fixture=pipeline", "-p", "127.0.0.1:%d:9092" % port]
        settings = {"KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
                    "KAFKA_LISTENERS": "PLAINTEXT://:9092,CONTROLLER://:9093",
                    "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://" + cls.broker,
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
                    "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                    "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT",
                    "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
                    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
                    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1"}
        for key, value in settings.items():
            options.extend(("-e", key + "=" + value))
        cls.container = cls.docker(*options, image("kafka"))
        cls.addClassCleanup(cls.docker, "rm", "--force", cls.container)
        deadline = time.monotonic() + 60
        while True:
            try:
                cls.admin = KafkaAdminClient(bootstrap_servers=cls.broker, bootstrap_timeout_ms=1000)
                cls.addClassCleanup(cls.admin.close)
                return
            except KafkaConfigurationError:
                raise
            except KafkaError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("fixture Kafka did not start: " + cls.docker("logs", "--tail", "20", cls.container))
                time.sleep(0.5)

    def test_real_consumer_ignores_history_noise_and_other_tenants_without_commits(self):
        from kafka import KafkaProducer
        from kafka.admin import NewTopic
        from kafka.serializer import Serializer

        class JsonSerializer(Serializer):
            def serialize(self, topic, headers, data):
                return json.dumps(data).encode()
        topic = "pipeline-" + uuid.uuid4().hex
        self.admin.create_topics([NewTopic(topic, 2, 1)])
        producer = KafkaProducer(bootstrap_servers=self.broker, acks="all",
                                 value_serializer=JsonSerializer())
        self.addCleanup(producer.close)
        probe = PIPELINE.probe_event()
        event = {**probe, "tenantId": "default", "msg": probe["message"]}
        producer.send(topic, event, partition=0).get(timeout=10)
        with patch.dict(os.environ, {"PIPELINE_KAFKA": self.broker}), \
                patch.object(PIPELINE, "TOPIC", topic), patch.object(PIPELINE, "TENANT", "default"):
            consumer = PIPELINE.KafkaProbe()
            self.addCleanup(consumer.close)
            self.assertIsNone(consumer.find(probe, timeout=0.25), "pre-injection matching history must not pass")
            for change in ({"eventId": "unrelated"}, {"tenantId": "foreign"}, {"host": "foreign"}, {"msg": "damaged"}):
                producer.send(topic, {**event, **change}, partition=1).get(timeout=10)
            self.assertIsNone(consumer.find(probe, timeout=0.25), "unrelated or altered events must not pass")
            receipt = producer.send(topic, event, partition=1).get(timeout=10)
            self.assertEqual(consumer.find(probe, timeout=5),
                             {"eventId": probe["eventId"], "partition": 1, "offset": receipt.offset})
            self.assertEqual(self.admin.list_groups(), [])

    def test_unknown_topic_does_not_get_created_by_metadata_probe(self):
        topic = "missing-" + uuid.uuid4().hex
        with patch.dict(os.environ, {"PIPELINE_KAFKA": self.broker}), patch.object(PIPELINE, "TOPIC", topic):
            with self.assertRaisesRegex(RuntimeError, "must exist"):
                PIPELINE.KafkaProbe()
        self.assertNotIn(topic, self.admin.list_topics())


if __name__ == "__main__":
    unittest.main()
