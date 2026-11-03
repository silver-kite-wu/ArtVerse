// Pure adapters shared by ordinary fields and optional structured imports.
interface FormField { key: string; type: string }
type Values = Record<string, unknown>;

function decodeContainer(value: unknown): unknown {
  if (typeof value !== 'string' || (!value.trim().startsWith('[') && !value.trim().startsWith('{'))) return value;
  try { return JSON.parse(value); } catch { return value; }
}
export function readableKnowledgeText(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(readableKnowledgeText).filter(v => v.trim()).join('\n');
  if (typeof value === 'object') return Object.entries(value).map(([key, item]) => `${key}：${readableKnowledgeText(item) || '未填写'}`).join('\n');
  return String(value);
}
export function normalizeFormValue(type: string, original: unknown): unknown {
  if (!['text', 'strings'].includes(type)) return original;
  const value = decodeContainer(original);
  if (type === 'text' && (typeof value === 'string' || (value !== null && typeof value === 'object'))) {
    const text = readableKnowledgeText(value); return text.trim() ? text : null;
  }
  if (type === 'strings') {
    if (typeof value === 'string') return value.split(/\r?\n/).map(v => v.trim()).filter(Boolean);
    if (Array.isArray(value) && value.every(v => v == null || typeof v === 'string')) return value.filter((v): v is string => typeof v === 'string').map(v => v.trim()).filter(Boolean);
  }
  return value;
}
export function normalizeFormData(data: Values, fields: FormField[]): Values {
  const result = {...data};
  for (const field of fields) if (Object.hasOwn(result, field.key)) result[field.key] = normalizeFormValue(field.type, result[field.key]);
  return result;
}
export function parseKnowledgeImport(raw: string, fields: FormField[]): Values {
  let value: unknown;
  try { value = JSON.parse(raw); } catch { throw new Error('导入内容无法识别，请检查复制是否完整；也可以直接填写上方表单。'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('请导入一份完整的字段对象，或直接填写上方表单。');
  return normalizeFormData(value as Values, fields);
}
