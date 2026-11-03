import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, MicOff, Bot, LoaderCircle } from "lucide-react";
import "./style.css";

const API = import.meta.env.VITE_API_URL || "http://127.0.0.1:8000";
const isMobileBrowser = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent || "");

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

export default function App() {
  const [active, setActive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("गुरी तैयार है");
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("माइक चालू करें और सीधे अपना आदेश बोलें। “गुरी” कहना जरूरी नहीं।");
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

    busyRef.current = true;
    setBusy(true);
    setStatus("आदेश मिला — काम शुरू कर रही हूँ…");

    try {
      // A mobile web page cannot operate other installed apps. Native Guri is required.
      if (
        isMobileBrowser &&
        /(instagram|इंस्टाग्राम|reels|रील|open app|ऐप खोल)/i.test(command)
      ) {
        const message = "आप अभी मोबाइल browser वाली Guri चला रहे हैं। यह browser दूसरी apps को tap या scroll नहीं कर सकता। Instagram control के लिए Guri Android app खोलें।";
        setReply(message);
        setStatus("Android app खोलें");
        await speakReply(message);
        return;
      }

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
      const message = error?.message || String(error);
      const explanation = /failed to fetch|networkerror|load failed/i.test(message)
        ? (isMobileBrowser
          ? "मोबाइल browser को अपने फोन पर Python backend नहीं मिला। फोन पर Instagram control करने के लिए native Guri Android app इस्तेमाल करें।"
          : "Python backend चालू नहीं है। Windows पर Start-Guri.bat पर double-click करें।")
        : message;
      const answer = "काम नहीं हो पाया: " + explanation;
      setReply(answer);
      setStatus("दिक्कत आई");
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
      <p className="guri-hint">माइक दबाएँ और सीधे आदेश बोलें — “गुरी” कहना जरूरी नहीं</p>
      <button className={"guri-mic" + (active ? " active" : "")} onClick={toggleListening} disabled={busy}>
        {active ? <MicOff size={23} /> : <Mic size={23} />}
        {active ? "सुनना बंद करें" : "Guri को सुनाएँ"}
      </button>
      {heard && <section className="guri-message"><small>आपने कहा</small><p>{heard}</p></section>}
      <section className="guri-message response"><small>Guri</small><p>{reply}</p></section>
      <p className="guri-footnote">Guri जवाब बोलकर सुनाएगी और अगला आदेश सुनने के लिए फिर तैयार होगी। मोबाइल apps को control करने के लिए Android Guri app इस्तेमाल करें।</p>
    </main>
  );
}
