import { useEffect, useRef, useState } from "react";
import {
  Bot, Send, Smartphone, Globe, Search, ShoppingBag,
  Activity, Mic, MicOff, Volume2,
} from "lucide-react";

const API = import.meta.env.VITE_API_URL || "http://127.0.0.1:8000";

async function readApiResponse(response) {
  const raw = await response.text();
  let data = {};
  try {
    data = raw ? JSON.parse(raw) : {};
  } catch {
    data = { detail: raw || ("HTTP " + response.status) };
  }
  if (!response.ok) {
    throw new Error(data.detail || data.message || ("HTTP " + response.status));
  }
  return data;
}

function explainError(error) {
  const message = error && error.message ? error.message : String(error);
  if (/failed to fetch|networkerror|load failed/i.test(message)) {
    return "Python backend से संपर्क नहीं हो पा रहा। backend terminal में uvicorn चालू करें और http://127.0.0.1:8000/api/health खोलकर जाँचें।";
  }
  return "काम नहीं हो पाया: " + message;
}

export default function App() {
  const [msg, setMsg] = useState([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("Connecting");
  const [devices, setDevices] = useState([]);
  const [listening, setListening] = useState(false);
  const [speak, setSpeak] = useState(true);
  const end = useRef(null);
  const recognition = useRef(null);

  useEffect(() => {
    fetch(API + "/api/health")
      .then(readApiResponse)
      .then(() => setStatus("Backend online"))
      .catch(() => setStatus("Backend offline"));
    refresh();
    fetch(API + "/api/history")
      .then(readApiResponse)
      .then((items) => setMsg(items.map((item) => ({ role: item.role, content: item.content }))))
      .catch(() => {});
    return () => {
      try { recognition.current?.stop(); } catch {}
    };
  }, []);

  useEffect(() => end.current?.scrollIntoView({ behavior: "smooth" }), [msg]);

  async function refresh() {
    try {
      const response = await fetch(API + "/api/mobile/devices");
      const data = await readApiResponse(response);
      setDevices(data.devices || []);
    } catch {
      setDevices([]);
    }
  }

  function say(text) {
    if (!speak || !("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "hi-IN";
    window.speechSynthesis.speak(utterance);
  }

  async function send(text = input) {
    const command = text.trim();
    if (!command || busy) return;
    setInput("");
    setMsg((items) => [...items, { role: "user", content: command }]);
    setBusy(true);
    try {
      const response = await fetch(API + "/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: command }),
      });
      const data = await readApiResponse(response);
      setMsg((items) => [...items, {
        role: "assistant",
        content: data.reply,
        results: data.results || [],
        status: data.status,
      }]);
      say(data.reply);
    } catch (error) {
      const detail = explainError(error);
      setMsg((items) => [...items, { role: "assistant", content: "त्रुटि: " + detail }]);
      say(detail);
    } finally {
      setBusy(false);
    }
  }

  function toggleVoice() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setMsg((items) => [...items, {
        role: "assistant",
        content: "इस browser में voice recognition उपलब्ध नहीं है। Chrome में microphone permission देकर कोशिश करें।",
      }]);
      return;
    }
    if (listening) {
      recognition.current?.stop();
      setListening(false);
      return;
    }
    const instance = new SpeechRecognition();
    recognition.current = instance;
    instance.lang = "hi-IN";
    instance.interimResults = false;
    instance.maxAlternatives = 1;
    instance.onresult = (event) => {
      const spoken = event.results?.[0]?.[0]?.transcript || "";
      setListening(false);
      setInput(spoken);
      send(spoken);
    };
    instance.onerror = () => setListening(false);
    instance.onend = () => setListening(false);
    instance.start();
    setListening(true);
  }

  async function mobile(path, body = {}) {
    setBusy(true);
    try {
      const response = await fetch(API + path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = await readApiResponse(response);
      setMsg((items) => [...items, { role: "assistant", content: data.reply }]);
      say(data.reply);
      refresh();
    } catch (error) {
      const detail = explainError(error);
      setMsg((items) => [...items, { role: "assistant", content: "त्रुटि: " + detail }]);
      say(detail);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app">
      <aside>
        <div className="brand"><Bot /><span><b>ORBIT AGENT</b><small>PERSONAL AUTOMATION</small></span></div>
        <label>WORKSPACE</label>
        <div className="nav">⌘　Agent Console</div>
        <label>QUICK ACTIONS</label>
        <button onClick={() => send("Open Amazon")}><ShoppingBag />Open Amazon</button>
        <button onClick={() => send("Open Google")}><Search />Open Google</button>
        <button onClick={() => send("Open YouTube")}><Globe />Open YouTube</button>
        <button onClick={() => send("New Tab में ChatGPT खोलो")}><Bot />Open ChatGPT</button>
        <label>ANDROID PHONE (USB)</label>
        <div className="device"><Smartphone size={16} />{devices.join(", ") || "No authorized device"}</div>
        <button onClick={() => mobile("/api/mobile/instagram/open")}>◎　Open Instagram</button>
        <button onClick={() => mobile("/api/mobile/instagram/reels/scroll", { swipes: 3 })}><Activity />Scroll Reels × 3</button>
        <div className="status">{status}<small>LOCAL DEVELOPMENT</small></div>
      </aside>

      <main>
        <header>
          <div><small>YOUR LOCAL AI WORKSPACE</small><h1>Agent Console</h1></div>
          <span>● PYTHON + REACT</span>
        </header>
        <section className="welcome">
          <Bot size={34} />
          <div>
            <h2>Tell me naturally. I’ll handle the task.</h2>
            <p>Type or speak in Hindi or English. The agent will perform available actions and report the result.</p>
          </div>
        </section>
        <section className="shortcuts">
          <button onClick={() => send("Amazon पर सबसे सस्ती watch खोजो")}>🛍 Find a product<small>Amazon price results</small></button>
          <button onClick={() => send("Google पर Python automation खोजो")}>⌕ Search web<small>Search snippets</small></button>
          <button onClick={() => send("New Tab में ChatGPT खोलो और उसको मैसेज करो कि क्या काम कर रहे हो")}>✉ Ask ChatGPT<small>Open tab and send a message</small></button>
        </section>
        <section className="chat">
          <div className="chathead">
            <Activity size={15} /> AGENT ACTIVITY
            <small>{msg.length} messages</small>
            <button className="speak-toggle" onClick={() => setSpeak((value) => !value)} title="Toggle spoken replies">
              <Volume2 size={15} />{speak ? "Voice on" : "Voice off"}
            </button>
          </div>
          <div className="feed">
            {!msg.length && (
              <div className="empty">
                <Bot size={32} /><b>Your agent is ready</b>
                <p>Try “New Tab में ChatGPT खोलो और उसको मैसेज करो कि क्या काम कर रहे हो”.</p>
              </div>
            )}
            {msg.map((item, index) => (
              <article className={item.role} key={index}>
                <b>{item.role === "user" ? "YOU" : "ORBIT AGENT"}</b>
                <p>{item.content}</p>
                {item.results?.map((result, resultIndex) => (
                  <div className="result" key={resultIndex}>
                    <strong>{resultIndex + 1}. {result.title}</strong>
                    <span>{result.price || result.snippet || ""}</span>
                    {result.url && <a href={result.url} target="_blank" rel="noreferrer">Open result ↗</a>}
                  </div>
                ))}
              </article>
            ))}
            {busy && <div className="working">Agent is working…</div>}
            <div ref={end} />
          </div>
          <form onSubmit={(event) => { event.preventDefault(); send(); }}>
            <input
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="बोलिए या अपना काम लिखिए…"
              disabled={busy}
            />
            <button type="button" className={listening ? "mic listening" : "mic"} onClick={toggleVoice} disabled={busy} title="Speak a command">
              {listening ? <MicOff size={18} /> : <Mic size={18} />}
            </button>
            <button type="submit" disabled={busy || !input.trim()}><Send size={18} /></button>
          </form>
          <footer>Microphone requires browser permission · ChatGPT requires a one-time login in the Orbit browser window</footer>
        </section>
      </main>
    </div>
  );
}
