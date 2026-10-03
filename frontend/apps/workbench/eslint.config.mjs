import tseslint from 'typescript-eslint'
import vue from 'eslint-plugin-vue'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('.', import.meta.url))
export default [
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
  ...vue.configs['flat/essential'],
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser, project: './tsconfig.json', tsconfigRootDir: root, extraFileExtensions: ['.vue'], vueFeatures: { filter: false } },
    },
    plugins: { '@typescript-eslint': tseslint.plugin },
    rules: {
      'no-debugger': 'error',
      'no-console': ['error', { allow: ['warn', 'error', 'info', 'debug'] }],
      'no-constant-binary-expression': 'error',
      'no-unsafe-optional-chaining': 'error',
      '@typescript-eslint/await-thenable': 'error',
      '@typescript-eslint/no-misused-spread': 'error',
    },
  },
  { files: ['src/**/*.ts'], languageOptions: { parser: tseslint.parser } },
]
