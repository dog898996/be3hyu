import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'tools/verify-foreground-service.py'
spec = importlib.util.spec_from_file_location('foreground_probe', path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
PKG = 'com.hoyoung.assistant'


def record(name, foreground):
    return f'  * ServiceRecord{{abc u0 {PKG}/.runtime.{name} c:pkg}}\n    isForeground={foreground} foregroundId=100\n'


class ForegroundProbeTest(unittest.TestCase):
    def test_actual_service(self):
        self.assertTrue(probe.foreground_present(record('AutonomyService', 'true'), PKG))

    def test_unrelated_foreground_cannot_supply_proof(self):
        self.assertFalse(probe.foreground_present(record('AutonomyService', 'false') + record('OtherService', 'true'), PKG))

    def test_history_or_connection_mention_is_not_live_service(self):
        self.assertFalse(probe.foreground_present('ConnectionRecord{ com.hoyoung.assistant/.runtime.AutonomyService }\n isForeground=true\n', PKG))

    def test_absent_or_stopped_service(self):
        for dump in ('', record('AutonomyService', 'false'), record('OtherService', 'true')):
            self.assertFalse(probe.foreground_present(dump, PKG))

    def test_other_package_or_component_suffix(self):
        self.assertFalse(probe.foreground_present(record('AutonomyServiceExtra', 'true'), PKG))
        self.assertFalse(probe.foreground_present(record('AutonomyService', 'true').replace(PKG, PKG + '.test'), PKG))

    def test_foreground_field_not_a_substring(self):
        self.assertFalse(probe.foreground_present(record('AutonomyService', 'trueish'), PKG))


if __name__ == '__main__':
    unittest.main()
