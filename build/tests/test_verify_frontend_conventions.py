import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_frontend_conventions", ROOT / "build" / "verify-frontend-conventions.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FrontendConventionsTest(unittest.TestCase):
    def check(self, source):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Example.vue").write_text(source, encoding="utf-8")
            errors = []
            with patch.object(MODULE, "ROOT", root), patch.object(MODULE, "SRC", root):
                count = MODULE.check_sortable(errors)
            return count, errors

    def test_handler_on_another_table_does_not_hide_dead_sort(self):
        count, errors = self.check('''<template>
          <el-table @sort-change="sortFirst"><el-table-column sortable="custom" /></el-table>
          <el-table><el-table-column sortable="custom" /></el-table>
        </template>''')
        self.assertEqual(count, 2)
        self.assertEqual(len(errors), 1)
        self.assertIn("Example.vue:3:", errors[0])

    def test_comments_and_script_strings_are_not_bindings(self):
        _, errors = self.check('''<script setup>const example = '@sort-change="sort"'</script>
        <template><!-- @sort-change="sort" -->
          <el-table><el-table-column sortable="custom" /></el-table>
        </template>''')
        self.assertEqual(len(errors), 1)

    def test_custom_sort_examples_in_comments_and_scripts_are_ignored(self):
        count, errors = self.check('''<script setup>const example = 'sortable="custom"'</script>
          <!-- <el-table-column sortable="custom" /> -->
          <template><el-table><el-table-column sortable /></el-table></template>''')
        self.assertEqual((count, errors), (0, []))

    def test_long_form_event_binding_and_quoted_greater_than_are_supported(self):
        count, errors = self.check('''<template>
          <ElTable :data="count > 0 ? rows : []" v-on:sort-change="sort">
            <ElTableColumn sortable='custom' />
          </ElTable>
        </template>''')
        self.assertEqual((count, errors), (1, []))

    def test_nested_table_cannot_borrow_parent_handler(self):
        count, errors = self.check('''<template><el-table @sort-change="sort">
          <el-table-column sortable="custom"><template #default>
            <el-table><el-table-column sortable="custom" /></el-table>
          </template></el-table-column>
        </el-table></template>''')
        self.assertEqual(count, 2)
        self.assertEqual(len(errors), 1)

    def test_empty_handler_is_not_a_sort_binding(self):
        _, errors = self.check('''<el-table @sort-change="">
          <el-table-column sortable="custom" /></el-table>''')
        self.assertEqual(len(errors), 1)

    def test_static_bound_custom_sort_and_event_modifiers(self):
        count, errors = self.check('''<el-table @sort-change.once="sort">
          <el-table-column :sortable="'custom'" />
          <el-table-column v-bind:sortable="'custom'" />
        </el-table>''')
        self.assertEqual((count, errors), (1, []))


if __name__ == "__main__":
    unittest.main()
