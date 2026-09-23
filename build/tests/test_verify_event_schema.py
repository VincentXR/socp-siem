import copy
import contextlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_event_schema', ROOT / 'build/verify-event-schema.py')
SCHEMA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCHEMA)


class EventSchemaCompatibilityTest(unittest.TestCase):
    def test_enum_narrowing_is_breaking(self):
        with self.assertRaisesRegex(ValueError, 'enum'):
            SCHEMA.validate_compatible({'type': 'string', 'enum': ['LOW', 'HIGH']}, {'type': 'string', 'enum': ['HIGH']})

    def test_constraint_tightening_is_breaking(self):
        for key, old, new in [('minLength', 1, 2), ('maxLength', 128, 64), ('minimum', 0, 1),
                              ('maximum', 10, 9), ('exclusiveMinimum', 0, 1), ('exclusiveMaximum', 10, 9),
                              ('minItems', 0, 1), ('maxItems', 10, 9), ('minProperties', 0, 1), ('maxProperties', 10, 9)]:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, key):
                SCHEMA.validate_compatible({key: old}, {key: new})

    def test_constraint_widening_is_safe(self):
        SCHEMA.validate_compatible({'type': 'string', 'minLength': 2, 'maxLength': 64, 'enum': ['LOW']},
                                   {'type': ['string', 'null'], 'minLength': 1, 'maxLength': 128, 'enum': ['LOW', 'HIGH']})
        SCHEMA.validate_compatible({'type': 'integer'}, {'type': 'number'})

    def test_pattern_format_and_const_changes_fail(self):
        for old, new in [({'pattern': '^a'}, {'pattern': '^b'}), ({}, {'format': 'date-time'}),
                         ({'const': '1.0'}, {'const': '1.1'}), ({'const': True}, {'const': 1})]:
            with self.subTest(old=old, new=new), self.assertRaises(ValueError):
                SCHEMA.validate_compatible(old, new)

    def test_nested_required_fields_and_types_are_checked(self):
        old = {'type': 'object', 'properties': {'fields': {'type': 'object', 'properties': {'actor': {'type': 'string'}}}}}
        for nested in [{'type': 'object', 'required': ['actor']},
                       {'type': 'object', 'properties': {'actor': {'type': 'integer'}}}]:
            with self.subTest(nested=nested), self.assertRaises(ValueError):
                SCHEMA.validate_compatible(old, {'type': 'object', 'properties': {'fields': nested}})

    def test_nested_additional_property_types_cannot_narrow(self):
        old = {'properties': {'fields': {'type': 'object', 'additionalProperties': {'type': ['string', 'number']}}}}
        new = copy.deepcopy(old)
        new['properties']['fields']['additionalProperties']['type'] = 'string'
        with self.assertRaisesRegex(ValueError, r'fields\.\*'):
            SCHEMA.validate_compatible(old, new)

    def test_closing_an_open_object_is_breaking(self):
        with self.assertRaises(ValueError):
            SCHEMA.validate_compatible({'type': 'object'}, {'type': 'object', 'additionalProperties': False})

    def test_new_optional_typed_field_in_an_open_object_is_not_automatically_safe(self):
        with self.assertRaises(ValueError):
            SCHEMA.validate_compatible({'type': 'object'}, {'type': 'object', 'properties': {'extra': {'type': 'string'}}})

    def test_optional_field_can_be_added_to_a_previously_closed_object(self):
        SCHEMA.validate_compatible({'type': 'object', 'additionalProperties': False},
                                   {'type': 'object', 'additionalProperties': False, 'properties': {'extra': {'type': 'string'}}})

    def test_removing_a_field_declaration_is_safe_only_if_old_values_remain_allowed(self):
        old = {'type': 'object', 'properties': {'known': {'type': 'string'}}}
        SCHEMA.validate_compatible(old, {'type': 'object'})
        with self.assertRaises(ValueError):
            SCHEMA.validate_compatible(old, {'type': 'object', 'additionalProperties': False})

    def test_array_items_and_uniqueness_are_checked(self):
        for new in [{'type': 'array', 'items': {'type': 'integer'}}, {'type': 'array', 'uniqueItems': True}]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                SCHEMA.validate_compatible({'type': 'array', 'items': {'type': 'number'}}, new)

    def test_unsupported_interacting_keywords_fail_closed(self):
        old = {'type': 'object', 'properties': {'known': {'type': 'string'}}, 'unevaluatedProperties': False}
        with self.assertRaisesRegex(ValueError, 'cannot prove compatibility'):
            SCHEMA.validate_compatible(old, {'type': 'object', 'unevaluatedProperties': False})
        SCHEMA.validate_compatible(old, {**old, 'description': 'annotation only'})

    def test_annotations_and_removing_constraints_are_safe(self):
        SCHEMA.validate_compatible({'type': 'string', 'format': 'date-time', 'description': 'before'}, {'type': 'string'})
        SCHEMA.validate_compatible({'$id': 'old', 'type': 'string'}, {'$id': 'new', 'type': 'string'})

    def test_id_change_cannot_silently_retarget_relative_references(self):
        old = {'$id': 'https://old.example/schema', 'properties': {'value': {'$ref': 'types.json'}}}
        with self.assertRaisesRegex(ValueError, 'base URI'):
            SCHEMA.validate_compatible(old, {**old, '$id': 'https://new.example/schema'})

    def test_numeric_schema_order_prevents_lexical_version_reversal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            for minor in (0, 2, 10):
                (path / f'canonical-event-1.{minor}.json').write_text(json.dumps({}), encoding='utf-8')
            with patch.object(SCHEMA, 'SCHEMA_DIR', path):
                self.assertEqual([0, 2, 10], [item[1] for item in SCHEMA.load_schemas()])

    def test_actual_schema_detects_severity_narrowing(self):
        old = json.loads((ROOT / 'schemas/canonical-event-1.0.json').read_text(encoding='utf-8'))
        new = copy.deepcopy(old)
        new['properties']['severity']['enum'].remove('INFO')
        with self.assertRaisesRegex(ValueError, 'severity'):
            SCHEMA.validate_compatible(old, new)

    def test_main_gate_rejects_a_narrowed_new_version(self):
        old = json.loads((ROOT / 'schemas/canonical-event-1.0.json').read_text(encoding='utf-8'))
        new = copy.deepcopy(old)
        new['properties']['msg']['maxLength'] = 100
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            for version, value in [('1.0', old), ('1.1', new)]:
                (path / f'canonical-event-{version}.json').write_text(json.dumps(value), encoding='utf-8')
            with patch.object(SCHEMA, 'SCHEMA_DIR', path), contextlib.redirect_stderr(io.StringIO()) as output:
                self.assertEqual(1, SCHEMA.main())
            self.assertIn('maxLength', output.getvalue())


if __name__ == '__main__':
    unittest.main()
