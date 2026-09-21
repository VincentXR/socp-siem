"""Regression cases for recovery and chaos probes' partition lag oracle."""
import sys
import unittest
from collections import namedtuple
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kafka_offsets import offset_snapshot

TopicPartition = namedtuple("TopicPartition", "topic partition")
OffsetAndMetadata = namedtuple("OffsetAndMetadata", "offset metadata")


class KafkaOffsetsTest(unittest.TestCase):
    def setUp(self):
        self.empty = TopicPartition("events", 0)
        self.busy = TopicPartition("events", 1)

    def test_empty_uncommitted_partition_does_not_create_phantom_lag(self):
        # Actual recovery fixture: one empty partition and 28 committed records.
        result = offset_snapshot({self.empty: 0, self.busy: 28},
                                 {self.empty: OffsetAndMetadata(-1, ""),
                                  self.busy: OffsetAndMetadata(28, "")})
        self.assertEqual((28, 28, 0),
                         (result["end"], result["committed"], result["lag"]))

    def test_nonempty_uncommitted_partition_retains_full_backlog(self):
        for value in (-1, None, OffsetAndMetadata(-1, "")):
            with self.subTest(value=value):
                result = offset_snapshot({self.busy: 20}, {self.busy: value})
                self.assertEqual(20, result["lag"])
                self.assertEqual(0, result["committed"])

    def test_missing_commit_retains_full_backlog(self):
        self.assertEqual(20, offset_snapshot({self.busy: 20}, {})["lag"])

    def test_real_backlog_remains_and_unrelated_commits_are_ignored(self):
        result = offset_snapshot({self.busy: 48},
                                 {self.busy: 28, TopicPartition("other", 0): 100})
        self.assertEqual(20, result["lag"])
        self.assertEqual(28, result["committed"])

    def test_one_partition_cannot_cancel_another_partitions_lag(self):
        result = offset_snapshot({self.empty: 10, self.busy: 20},
                                 {self.empty: 30, self.busy: 10})
        self.assertEqual(10, result["lag"])
        self.assertEqual([0, 10], [p["lag"] for p in result["perPartition"]])

    def test_invalid_negative_offsets_fail_instead_of_reporting_recovery(self):
        for end, committed in ((20, -2), (-1, 0)):
            with self.subTest(end=end, committed=committed):
                with self.assertRaises(ValueError):
                    offset_snapshot({self.busy: end}, {self.busy: committed})
