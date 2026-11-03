import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, MicOff, Bot, LoaderCircle } from "lucide-react";
import "./style.css";

const API = import.meta.env.VITE_API_URL || "http://127.0.0.1:8000";

async function parseResponse(response) {
  const raw = await response.text();
  let data;
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

function hasWakeWord(value) {
  return /गुरी|\bguri\b/i.test(value || "");
}

export default function App() {
  const [active, setActive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("गुरी तैयार है");
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("माइक चालू करें और बोलें: गुरी, Instagram खोलो और Reels scroll करो।");
  const recognitionRef = useRef(null);
  const activeRef = useRef(false);
  const busyRef = useRef(false);
  const restartTimer = useRef(null);

  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  const startRecognition = useCallback(() => {
    if (!activeRef.current || busyRef.current || document.visibilityState !== "visible") return;
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setStatus("इस browser में voice recognition उपलब्ध नहीं है। Chrome इस्तेमाल करें।");
      setActive(false);
      activeRef.current = false;
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognitionRef.current = recognition;
      recognition.lang = "hi-IN";
      recognition.continuous = false;
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;
      recognition.onstart = () => setStatus("गुरी सुन रही है…");
      recognition.onresult = (event) => {
        const phrase = event.results?.[event.resultIndex]?.[0]?.transcript?.trim() || "";
        if (!phrase) return;
        setHeard(phrase);
        if (!hasWakeWord(phrase)) {
          setStatus("आदेश से पहले “गुरी” बोलें");
          return;
        }
        void runCommand(phrase);
      };
      recognition.onerror = (event) => {
        if (event.error === "not-allowed" || event.error === "service-not-allowed") {
          setStatus("Microphone permission दें");
          setActive(false);
          activeRef.current = false;
        } else if (event.error !== "no-speech" && event.error !== "aborted") {
          setStatus("Voice error: " + event.error);
        }
      };
      recognition.onend = () => {
        recognitionRef.current = null;
        if (activeRef.current && !busyRef.current && document.visibilityState === "visible") {
          restartTimer.current = window.setTimeout(startRecognition, 400);
        }
      };
      recognition.start();
    } catch (error) {
      setStatus("Microphone शुरू नहीं हुआ: " + (error?.message || error));
    }
  }, []);

  const runCommand = useCallback(async (command) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setStatus("काम कर रही हूँ…");
    try {
      const response = await fetch(API + "/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: command }),
      });
      const data = await parseResponse(response);
      setReply(data.reply || "काम पूरा हुआ।");
      setStatus(data.status === "needs_login" ? "Login जरूरी है" : "काम पूरा हुआ");
      if (data.status === "needs_login") {
        setActive(false);
        activeRef.current = false;
      }
    } catch (error) {
      const message = error?.message || String(error);
      const explanation = /failed to fetch|networkerror|load failed/i.test(message)
        ? "Python service चालू नहीं है। Guri को Start-Guri.bat से खोलें।"
        : message;
      setReply("काम नहीं हो पाया: " + explanation);
      setStatus("दिक्कत आई");
    } finally {
      busyRef.current = false;
      setBusy(false);
      if (activeRef.current && document.visibilityState === "visible") {
        restartTimer.current = window.setTimeout(startRecognition, 700);
      }
    }
  }, [startRecognition]);

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === "visible" && activeRef.current && !busyRef.current) {
        startRecognition();
      } else if (document.visibilityState !== "visible") {
        try { recognitionRef.current?.stop(); } catch {}
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.clearTimeout(restartTimer.current);
      try { recognitionRef.current?.stop(); } catch {}
    };
  }, [startRecognition]);

  function toggleListening() {
    if (activeRef.current) {
      activeRef.current = false;
      setActive(false);
      window.clearTimeout(restartTimer.current);
      try { recognitionRef.current?.stop(); } catch {}
      setStatus("गुरी pause है");
      return;
    }
    activeRef.current = true;
    setActive(true);
    setStatus("Microphone चालू हो रहा है…");
    startRecognition();
  }

  return (
    <main className="guri-shell">
      <div className={"guri-orb" + (active ? " listening" : "") + (busy ? " working" : "")}>
        {busy ? <LoaderCircle className="spin" size={54} /> : <Bot size={58} />}
      </div>
      <h1>Guri</h1>
      <p className="guri-status">{status}</p>
      <p className="guri-hint">“गुरी” कहकर अपना काम बताइए</p>
      <button className={"guri-mic" + (active ? " active" : "")} onClick={toggleListening} disabled={busy}>
        {active ? <MicOff size={23} /> : <Mic size={23} />}
        {active ? "सुनना बंद करें" : "Guri को सुनाएँ"}
      </button>
      {heard && <section className="guri-message"><small>आपने कहा</small><p>{heard}</p></section>}
      <section className="guri-message response"><small>Guri</small><p>{reply}</p></section>
      <p className="guri-footnote">Voice mode शुरू करने के लिए एक बार माइक दबाएँ। आदेश पूरा होने पर Guri फिर सुनना शुरू करेगी।</p>
    </main>
  );
}
