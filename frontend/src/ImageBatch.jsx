import { useEffect, useMemo, useState } from "react";
import { ImagePlus, Play, Pause, Square, RotateCcw, ListChecks } from "lucide-react";
import "./image-batch.css";

const API = (import.meta.env.VITE_API_URL || "").replace(/\/+$/, "");
const endpoint = (path) => API + path;

function parsePrompts(raw) {
  const text = (raw || "").trim();
  if (!text) return [];
  const numbered = text.split(/(?=^\s*(?:\d{1,3}[.)]|prompt\s+\d+\s*[:.)])\s*)/gim)
    .map((part) => part.replace(/^\s*(?:\d{1,3}[.)]|prompt\s+\d+\s*[:.)])\s*/i, "").trim())
    .filter(Boolean);
  if (numbered.length > 1) return numbered;
  const paragraphs = text.split(/\n\s*\n/g).map((part) => part.trim()).filter(Boolean);
  return paragraphs.length > 1 ? paragraphs : [text];
}

export default function ImageBatch() {
  const [raw, setRaw] = useState("");
  const [pairingCode, setPairingCode] = useState("");
  const [status, setStatus] = useState({
    state: "idle", total: 0, completed: 0, current: 0,
    current_prompt: "", results: [], error: null,
  });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const prompts = useMemo(() => parsePrompts(raw), [raw]);
  const active = ["running", "pausing", "paused"].includes(status.state);
  const headers = () => ({
    "Content-Type": "application/json",
    ...(pairingCode.trim() ? { "X-Guri-Pairing-Code": pairingCode.trim() } : {}),
  });

  async function request(path, options = {}) {
    const response = await fetch(endpoint(path), {
      ...options,
      headers: { ...headers(), ...(options.headers || {}) },
      cache: "no-store",
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.detail || body.message || "Request failed: HTTP " + response.status);
    return body;
  }

  async function refreshStatus() {
    try {
      const data = await request("/api/image-batch/status", { method: "GET", headers: {} });
      if (data.status) setStatus(data.status);
    } catch (error) {
      setMessage(error.message);
    }
  }

  useEffect(() => {
    if (!active && status.state !== "pausing") return undefined;
    const timer = window.setInterval(refreshStatus, 1200);
    return () => window.clearInterval(timer);
  }, [active, status.state, pairingCode]);

  useEffect(() => { void refreshStatus(); }, []);

  async function startBatch() {
    if (!prompts.length || prompts.length > 50) return;
    setBusy(true);
    setMessage("");
    try {
      const data = await request("/api/image-batch/start", {
        method: "POST",
        body: JSON.stringify({ prompts }),
      });
      if (data.status && data.status.state) setStatus(data.status);
      else await refreshStatus();
      setMessage("Batch queue भेज दी गई है। Chrome Agent एक-एक prompt चलाएगा।");
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  async function control(action) {
    setBusy(true);
    setMessage("");
    try {
      const data = await request("/api/image-batch/control", {
        method: "POST",
        body: JSON.stringify({ action }),
      });
      if (data.status && data.status.state) setStatus(data.status);
      else await refreshStatus();
      setMessage(action === "pause" ? "Pause request भेज दी गई। मौजूदा image पूरी होने के बाद queue रुकेगी।" :
        action === "resume" ? "Queue resume कर दी गई।" : "Batch cancel कर दी गई।");
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  const progress = status.total ? Math.min(100, Math.round((status.completed / status.total) * 100)) : 0;
  const stateLabel = {
    idle: "Ready", running: "Generating", pausing: "Pausing after current image",
    paused: "Paused", completed: "All images completed",
    failed: "Prompt failed — retry available", cancelled: "Cancelled", offline: "PC agent offline",
  }[status.state] || status.state;

  return (
    <section className="image-batch">
      <header className="image-batch-head">
        <div className="image-batch-icon"><ImagePlus size={21}/></div>
        <div><h2>Bulk Image Automation</h2><p>50 prompts तक paste करें — ChatGPT में एक-एक करके भेजे जाएँगे</p></div>
        <span className={"batch-state " + status.state}>{stateLabel}</span>
      </header>

      <label className="batch-label" htmlFor="batch-prompts">Image Prompts</label>
      <textarea
        id="batch-prompts"
        className="batch-textarea"
        value={raw}
        onChange={(event) => setRaw(event.target.value)}
        placeholder={"1. First image prompt...\n2. Second image prompt...\n3. Third image prompt..."}
        rows={8}
        disabled={active || busy}
      />
      <div className="batch-meta"><span>{prompts.length} prompts detected</span><span>Maximum 50 per batch</span></div>
      <label className="batch-label" htmlFor="batch-pairing">Cloud pairing code <small>(Vercel / remote dashboard only)</small></label>
      <input
        id="batch-pairing"
        className="batch-input"
        value={pairingCode}
        onChange={(event) => setPairingCode(event.target.value)}
        placeholder="Local PC mode में खाली छोड़ें"
        autoComplete="off"
      />

      <div className="batch-actions">
        {status.state === "failed" ? (
          <button className="batch-primary" onClick={() => control("resume")} disabled={busy}><RotateCcw size={16}/> Retry failed prompt</button>
        ) : status.state === "paused" ? (
          <button className="batch-primary" onClick={() => control("resume")} disabled={busy}><Play size={16}/> Resume</button>
        ) : (
          <button className="batch-primary" onClick={startBatch} disabled={busy || active || !prompts.length || prompts.length > 50}>
            <Play size={16}/> Start batch
          </button>
        )}
        {(status.state === "running" || status.state === "pausing") && (
          <button className="batch-secondary" onClick={() => control("pause")} disabled={busy}><Pause size={16}/> Pause</button>
        )}
        {active && <button className="batch-danger" onClick={() => control("cancel")} disabled={busy}><Square size={15}/> Cancel</button>}
      </div>

      {(status.total > 0 || status.state !== "idle") && (
        <div className="batch-progress">
          <div className="batch-progress-top"><strong>{status.completed} / {status.total} images</strong><span>{progress}%</span></div>
          <div className="batch-track"><div className="batch-fill" style={{ width: progress + "%" }}/></div>
          {status.current_prompt && <div className="batch-current"><b>Current prompt #{status.current || status.completed + 1}</b><p>{status.current_prompt}</p></div>}
          {status.error && <div className="batch-error">{status.error}</div>}
        </div>
      )}

      <div className="batch-results-head"><ListChecks size={16}/> Queue activity <span>{status.results?.length || 0} finished</span></div>
      <div className="batch-results">
        {(status.results || []).slice().reverse().map((item) => (
          <div className="batch-result" key={item.index}>
            <span className={item.status === "completed" ? "result-check" : "result-failed"}>{item.status === "completed" ? "✓" : "!"}</span>
            <div><b>Prompt {item.index}</b><small>{item.detail}</small></div>
          </div>
        ))}
        {!status.results?.length && <p className="batch-empty">अभी queue खाली है। ऊपर अपने numbered prompts paste करें।</p>}
      </div>
      {message && <p className="batch-message">{message}</p>}
      <p className="batch-note">Automation केवल आपके अपने ChatGPT login वाले Chrome session में काम करेगी। Login, CAPTCHA या usage-limit verification आने पर आपको manually पूरा करना होगा।</p>
    </section>
  );
}
