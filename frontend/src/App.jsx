import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, MicOff, Bot, LoaderCircle } from "lucide-react";
import "./style.css";

const API = import.meta.env.VITE_API_URL || "http://127.0.0.1:8000";
const isMobileBrowser = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent || "");
const isSecureHostedPage =
  window.location.protocol === "https:" &&
  !["localhost", "127.0.0.1"].includes(window.location.hostname);

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

function cleanCommand(phrase) {
  return (phrase || "")
    .replace(/^\s*(?:गुरी|guri)\s*[,،:;-]?\s*/i, "")
    .trim();
}

function explainConnectionError(error) {
  const message = error?.message || String(error);
  if (/failed to fetch|networkerror|load failed|fetch failed/i.test(message)) {
    if (isMobileBrowser) {
      return "यह Guri webpage है, Android app नहीं। फोन का browser Python backend तक नहीं पहुँच रहा। Instagram खोलने और scroll करने के लिए फोन में installed Guri Android app खोलें।";
    }
    if (isSecureHostedPage) {
      return "आप Guri को online HTTPS webpage से चला रहे हैं। यह webpage आपके PC के local Python service से सुरक्षित रूप से connect नहीं कर पा रहा। Windows पर repository के अंदर Start-Guri.bat चलाएँ और उसी PC पर http://127.0.0.1:5173 खोलें।";
    }
    return "इस कंप्यूटर पर Guri Python service उपलब्ध नहीं है। Start-Guri.bat इसी कंप्यूटर पर चलाएँ। फिर http://127.0.0.1:8000/api/health खोलें; वहाँ { ok: true, agent: Guri } दिखना चाहिए।";
  }
  return message;
}

export default function App() {
  const [active, setActive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("Guri service जाँच रही है…");
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("माइक दबाएँ और सीधे आदेश बोलें।");
  const [backendReady, setBackendReady] = useState(false);
  const recognitionRef = useRef(null);
  const activeRef = useRef(false);
  const busyRef = useRef(false);
  const restartTimer = useRef(null);

  const checkBackend = useCallback(async () => {
    if (isMobileBrowser) {
      setBackendReady(false);
      setStatus("मोबाइल पर Instagram के लिए Android Guri app खोलें");
      return false;
    }
    try {
      const response = await fetch(API + "/api/health", { cache: "no-store" });
      const data = await parseResponse(response);
      if (!data.ok || data.agent !== "Guri") {
        throw new Error("इस address पर Guri backend नहीं चल रहा।");
      }
      setBackendReady(true);
      setStatus("Guri connected — आवाज़ दें");
      return true;
    } catch (error) {
      setBackendReady(false);
      setStatus(explainConnectionError(error));
      return false;
    }
  }, []);

  useEffect(() => {
    void checkBackend();
    const interval = window.setInterval(() => {
      if (!activeRef.current && !busyRef.current) void checkBackend();
    }, 10000);
    return () => window.clearInterval(interval);
  }, [checkBackend]);

  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  const speakReply = useCallback((text) => new Promise((resolve) => {
    if (!("speechSynthesis" in window) || !text) {
      resolve();
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "hi-IN";
    utterance.onend = resolve;
    utterance.onerror = resolve;
    window.speechSynthesis.speak(utterance);
    window.setTimeout(resolve, 12000);
  }), []);

  const runCommand = useCallback(async (spokenText) => {
    if (busyRef.current) return;
    const command = cleanCommand(spokenText) || (spokenText || "").trim();
    if (!command) return;

    const isPhoneAutomation =
      /(instagram|इंस्टाग्राम|reels|रील|open app|ऐप खोल)/i.test(command);
    if (isMobileBrowser && isPhoneAutomation) {
      const message = "Instagram control के लिए Guri Android app खोलें। यह webpage दूसरी apps को tap या scroll नहीं कर सकता।";
      setReply(message);
      setStatus("Native Android Guri app इस्तेमाल करें");
      await speakReply(message);
      return;
    }

    busyRef.current = true;
    setBusy(true);
    setStatus("आदेश मिला — काम शुरू कर रही हूँ…");

    try {
      const healthResponse = await fetch(API + "/api/health", { cache: "no-store" });
      const health = await parseResponse(healthResponse);
      if (!health.ok || health.agent !== "Guri") {
        throw new Error("Guri local backend गलत या unavailable है।");
      }
      setBackendReady(true);

      const response = await fetch(API + "/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: command }),
      });
      const data = await parseResponse(response);
      const answer = data.reply || "काम पूरा हुआ।";
      setReply(answer);
      if (data.status === "needs_login") {
        setStatus("ChatGPT में एक बार login करें");
        activeRef.current = false;
        setActive(false);
      } else if (data.status === "partial") {
        setStatus("काम आंशिक रूप से हुआ");
      } else {
        setStatus("काम पूरा हुआ");
      }
      await speakReply(answer);
    } catch (error) {
      setBackendReady(false);
      const answer = "काम नहीं हो पाया: " + explainConnectionError(error);
      setReply(answer);
      setStatus("Backend connection नहीं है");
      await speakReply(answer);
    } finally {
      busyRef.current = false;
      setBusy(false);
      if (activeRef.current && document.visibilityState === "visible") {
        restartTimer.current = window.setTimeout(startRecognition, 700);
      }
    }
  }, [speakReply]);

  const startRecognition = useCallback(() => {
    if (!activeRef.current || busyRef.current || document.visibilityState !== "visible") return;
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setStatus("इस browser में voice recognition नहीं है। Chrome इस्तेमाल करें।");
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
        void runCommand(phrase);
      };
      recognition.onerror = (event) => {
        if (event.error === "not-allowed" || event.error === "service-not-allowed") {
          setStatus("Microphone permission दें");
          activeRef.current = false;
          setActive(false);
        } else if (event.error === "no-speech") {
          setStatus("आवाज़ नहीं मिली — फिर सुन रही हूँ…");
        } else if (event.error !== "aborted") {
          setStatus("Voice error: " + event.error);
        }
      };
      recognition.onend = () => {
        recognitionRef.current = null;
        if (activeRef.current && !busyRef.current && document.visibilityState === "visible") {
          restartTimer.current = window.setTimeout(startRecognition, 450);
        }
      };
      recognition.start();
    } catch (error) {
      setStatus("Microphone शुरू नहीं हुआ: " + (error?.message || error));
      if (activeRef.current) {
        restartTimer.current = window.setTimeout(startRecognition, 1000);
      }
    }
  }, [runCommand]);

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
      if ("speechSynthesis" in window) window.speechSynthesis.cancel();
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
      <p className="guri-hint">
        {backendReady ? "माइक दबाएँ और सीधे आदेश बोलें" : "पहले Guri local service से connection चाहिए"}
      </p>
      <button
        className={"guri-mic" + (active ? " active" : "")}
        onClick={toggleListening}
        disabled={busy || (!isMobileBrowser && !backendReady)}
        title={!isMobileBrowser && !backendReady ? "पहले local Guri backend start करें" : "Voice command"}
      >
        {active ? <MicOff size={23} /> : <Mic size={23} />}
        {active ? "सुनना बंद करें" : backendReady || isMobileBrowser ? "Guri को सुनाएँ" : "Backend कनेक्ट नहीं है"}
      </button>
      {heard && <section className="guri-message"><small>आपने कहा</small><p>{heard}</p></section>}
      <section className="guri-message response"><small>Guri</small><p>{reply}</p></section>
      <p className="guri-footnote">
        {isMobileBrowser
          ? "फोन पर Instagram control के लिए installed Guri Android app खोलें।"
          : "Guri desktop commands के लिए Python backend इसी PC पर चालू होना जरूरी है।"}
      </p>
    </main>
  );
}
