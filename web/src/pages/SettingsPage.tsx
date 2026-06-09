import { useEffect, useState } from 'react';
import { Settings, UserSettings } from '../services/api';
import { useToast } from '../context/ToastContext';

export default function SettingsPage() {
  const [settings,  setSettings]  = useState<UserSettings | null>(null);
  const [defaults,  setDefaults]  = useState<UserSettings | null>(null);
  const [loading,   setLoading]   = useState(true);
  const [saving,    setSaving]    = useState(false);
  const [dirty,     setDirty]     = useState(false);
  const { show } = useToast();

  useEffect(() => {
    Settings.get()
      .then((r) => { setSettings(r.data.settings); setDefaults(r.data.defaults); })
      .catch(() => show('Failed to load settings', 'error'))
      .finally(() => setLoading(false));
  }, [show]);

  function update<S extends keyof UserSettings>(section: S, field: keyof UserSettings[S], value: unknown) {
    setSettings((prev) => prev ? { ...prev, [section]: { ...prev[section], [field]: value } } : prev);
    setDirty(true);
  }

  async function save() {
    if (!settings) return;
    setSaving(true);
    try {
      const res = await Settings.patch(settings);
      setSettings(res.data.settings);
      setDirty(false);
      show('Settings saved', 'success');
    } catch { show('Failed to save settings', 'error'); }
    finally { setSaving(false); }
  }

  async function reset() {
    try {
      const res = await Settings.reset();
      setSettings(res.data.settings);
      setDirty(false);
      show('Settings reset to defaults', 'info');
    } catch { show('Reset failed', 'error'); }
  }

  if (loading || !settings) return <p style={muted}>Loading…</p>;

  return (
    <div style={{ padding: 24, maxWidth: 760, display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h1 style={h1}>Settings</h1>
        <div style={{ display: 'flex', gap: 10 }}>
          <button style={{ ...btn, background: 'var(--border)', color: 'var(--text)' }} onClick={reset}>
            Reset to defaults
          </button>
          <button style={{ ...btn, opacity: (!dirty || saving) ? .5 : 1 }} onClick={save} disabled={!dirty || saving}>
            {saving ? 'Saving…' : dirty ? 'Save changes' : 'Saved'}
          </button>
        </div>
      </div>

      {dirty && (
        <div style={unsavedBanner}>Unsaved changes — click "Save changes" to apply.</div>
      )}

      {/* AI Settings */}
      <Card title="🤖 AI / LLM" description="Configure how P-Sphere connects to your local language model.">
        <Field label="Provider" hint="ollama | lmstudio | openai-compat">
          <Select
            value={settings.ai.provider}
            options={['ollama', 'lmstudio', 'openai-compat']}
            onChange={(v) => update('ai', 'provider', v)}
          />
        </Field>
        <Field label="Base URL" hint={`Default: ${defaults?.ai.baseUrl}`}>
          <TextInput value={settings.ai.baseUrl} onChange={(v) => update('ai', 'baseUrl', v)} placeholder="http://localhost:11434" />
        </Field>
        <Field label="Model" hint={`Default: ${defaults?.ai.model}`}>
          <TextInput value={settings.ai.model} onChange={(v) => update('ai', 'model', v)} placeholder="llama3" />
        </Field>
      </Card>

      {/* Storage Settings */}
      <Card title="💾 Storage" description="Control where files are stored and upload limits.">
        <Field label="Data root path" hint="Directory for photos, thumbs, and temp uploads">
          <TextInput value={settings.storage.dataRootPath} onChange={(v) => update('storage', 'dataRootPath', v)} placeholder="./data" />
        </Field>
        <Field label="Max upload size (MB)" hint="Per-file limit (default 500 MB)">
          <NumberInput
            value={settings.storage.maxUploadMB}
            min={10} max={5000}
            onChange={(v) => update('storage', 'maxUploadMB', v)}
          />
        </Field>
      </Card>

      {/* Notification Preferences */}
      <Card title="🔔 Notifications" description="Choose which events trigger notifications.">
        <Toggle
          label="Sync complete"
          description="Notify when a sync job finishes"
          checked={settings.notifications.syncComplete}
          onChange={(v) => update('notifications', 'syncComplete', v)}
        />
        <Toggle
          label="New duplicates detected"
          description="Alert when the perceptual hash scan finds new duplicate groups"
          checked={settings.notifications.newDuplicates}
          onChange={(v) => update('notifications', 'newDuplicates', v)}
        />
        <Toggle
          label="Weekly digest"
          description="Receive a weekly summary of library stats and new photos"
          checked={settings.notifications.weeklyDigest}
          onChange={(v) => update('notifications', 'weeklyDigest', v)}
        />
      </Card>

      {/* Vision Worker Settings */}
      <Card title="👁 Vision Worker" description="ONNX model paths and inference thresholds.">
        <Field label="Models directory" hint="Folder containing .onnx model files">
          <TextInput value={settings.vision.modelsDir} onChange={(v) => update('vision', 'modelsDir', v)} placeholder="./models" />
        </Field>
        <Field label="Face detection confidence" hint="Min score to accept a face bounding box (0.0–1.0)">
          <RangeInput
            value={settings.vision.faceDetectionConfidence}
            min={0.1} max={0.95} step={0.05}
            onChange={(v) => update('vision', 'faceDetectionConfidence', v)}
          />
        </Field>
        <Field label="Object tag confidence" hint="Min softmax score to include an object tag (0.01–0.5)">
          <RangeInput
            value={settings.vision.objectTagConfidence}
            min={0.01} max={0.5} step={0.01}
            onChange={(v) => update('vision', 'objectTagConfidence', v)}
          />
        </Field>
      </Card>
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function Card({ title, description, children }: { title: string; description: string; children: React.ReactNode }) {
  return (
    <div style={card}>
      <h2 style={cardTitle}>{title}</h2>
      <p style={cardDesc}>{description}</p>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>{children}</div>
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint: string; children: React.ReactNode }) {
  return (
    <div style={field}>
      <div style={{ flex: '0 0 220px' }}>
        <div style={fieldLabel}>{label}</div>
        <div style={fieldHint}>{hint}</div>
      </div>
      <div style={{ flex: 1 }}>{children}</div>
    </div>
  );
}

function TextInput({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <input
      style={inputStyle}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
    />
  );
}

function NumberInput({ value, min, max, onChange }: { value: number; min: number; max: number; onChange: (v: number) => void }) {
  return (
    <input
      style={inputStyle}
      type="number" min={min} max={max}
      value={value}
      onChange={(e) => onChange(parseInt(e.target.value, 10))}
    />
  );
}

function Select({ value, options, onChange }: { value: string; options: string[]; onChange: (v: string) => void }) {
  return (
    <select style={{ ...inputStyle, cursor: 'pointer' }} value={value} onChange={(e) => onChange(e.target.value)}>
      {options.map((o) => <option key={o} value={o}>{o}</option>)}
    </select>
  );
}

function RangeInput({ value, min, max, step, onChange }: { value: number; min: number; max: number; step: number; onChange: (v: number) => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <input
        type="range" min={min} max={max} step={step}
        value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        style={{ flex: 1, accentColor: 'var(--primary)' }}
      />
      <span style={{ minWidth: 36, textAlign: 'right', fontSize: 13, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </span>
    </div>
  );
}

function Toggle({ label, description, checked, onChange }: { label: string; description: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
      <div>
        <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--text)', marginBottom: 2 }}>{label}</div>
        <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{description}</div>
      </div>
      <button
        onClick={() => onChange(!checked)}
        style={{
          width: 44, height: 24, borderRadius: 12, border: 'none', cursor: 'pointer', flexShrink: 0,
          background: checked ? 'var(--primary)' : 'var(--border)',
          position: 'relative', transition: 'background .2s',
        }}
      >
        <span style={{
          position: 'absolute', top: 3, left: checked ? 22 : 3,
          width: 18, height: 18, borderRadius: '50%', background: '#fff',
          transition: 'left .2s',
        }} />
      </button>
    </div>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const h1:           React.CSSProperties = { fontSize: 22, fontWeight: 700, color: 'var(--text)' };
const btn:          React.CSSProperties = { background: 'var(--primary)', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 18px', cursor: 'pointer', fontSize: 14, transition: 'opacity .15s' };
const unsavedBanner:React.CSSProperties = { background: 'var(--surface2)', border: '1px solid var(--accent)', borderRadius: 8, padding: '10px 16px', fontSize: 13, color: 'var(--accent)' };
const card:         React.CSSProperties = { background: 'var(--surface)', borderRadius: 12, padding: 24 };
const cardTitle:    React.CSSProperties = { fontSize: 16, fontWeight: 600, color: 'var(--text)', marginBottom: 4 };
const cardDesc:     React.CSSProperties = { fontSize: 13, color: 'var(--text-muted)', marginBottom: 20 };
const field:        React.CSSProperties = { display: 'flex', gap: 16, alignItems: 'flex-start' };
const fieldLabel:   React.CSSProperties = { fontSize: 14, fontWeight: 500, color: 'var(--text)', marginBottom: 3 };
const fieldHint:    React.CSSProperties = { fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.5 };
const inputStyle:   React.CSSProperties = { background: 'var(--input-bg)', border: '1px solid var(--border)', borderRadius: 8, padding: '9px 12px', color: 'var(--text)', fontSize: 14, width: '100%' };
const muted:        React.CSSProperties = { color: 'var(--text-muted)', fontSize: 13 };
