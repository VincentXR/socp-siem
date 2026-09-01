import copy
import sys
from pathlib import Path
import unittest


BUILD = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BUILD))

from runtime_topology import (
    current_registry,
    load_topology,
    validate_registry,
    validate_topology,
)


class RuntimeTopologyTest(unittest.TestCase):

    def test_repository_topology_assigns_current_services_once(self):
        modules, services = current_registry()

        self.assertEqual([], validate_registry(modules, services))
        self.assertEqual([], validate_topology(load_topology(), modules, services))

    def test_registry_rejects_non_executable_default_process(self):
        modules, services = current_registry()

        errors = validate_registry(modules, [*services, "missing-service"])

        self.assertTrue(any("not executable modules" in error for error in errors))

    def test_duplicate_assignment_is_rejected(self):
        modules, services = current_registry()
        topology = copy.deepcopy(load_topology())
        topology["units"][1]["members"].append("api-gateway")

        errors = validate_topology(topology, modules, services)

        self.assertTrue(any("multiple runtime units" in error for error in errors))

    def test_compatibility_launcher_cannot_become_a_target_member(self):
        modules, services = current_registry()
        topology = copy.deepcopy(load_topology())
        compatibility = "synthetic-compatibility-launcher"
        modules.append(compatibility)
        topology["compatibilityModules"].append(compatibility)
        topology["units"][1]["members"].append(compatibility)

        errors = validate_topology(topology, modules, services)

        self.assertTrue(any("compatibility launchers" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
