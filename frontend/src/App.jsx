import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, MicOff, Bot, LoaderCircle, Terminal, Monitor, Smartphone, Wifi } from "lucide-react";
import "./style.css";

const API = (import.meta.env.VITE_API_URL || "").replace(/\/+$/, "");
const API_URL = (path) => API + path;

async function readApi(response) {
  const raw = await response.text();
  let data = {};
  try { data = raw ? JSON.parse(raw) : {}; }
  catch { data = { detail: raw || "Unexpected server response" }; }
  if (!response.ok) {
    if (response.status === 404 && /\.vercel\.app$/i.test(window.location.hostname)) {
      throw new Error("आप Vercel की remote website खोल रहे हैं। अपने PC पर Start-Guri.bat चलाएँ; वह Guri का local runtime और voice screen अपने आप खोलेगा।");
    }
    throw new Error(data.detail || data.message || ("HTTP " + response.status));
  }
  return data;
}

function cleanCommand(value) {
  return (value || "").replace(/^\s*(?:गुरी|guri)\s*[,،:;-]?\s*/i, "").trim();
}

export default function App() {
  const [listening, setListening] = useState(false);
  const [working, setWorking] = useState(false);
  const [status, setStatus] = useState("Guri शुरू हो रही है…");
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("माइक दबाएँ और अपना काम बोलें।");
  const [apiReady, setApiReady] = useState(false);
  const [phoneStatus, setPhoneStatus] = useState("USB phone not checked");
  const [events, setEvents] = useState([]);
  const recognitionRef = useRef(null);
  const listeningRef = useRef(false);
  const workingRef = useRef(false);
  const restartRef = useRef(null);

  const addEvent = useCallback((label, detail, state = "info") => {
    setEvents((items) => [{ id: Date.now() + Math.random(), label, detail, state, time: new Date().toLocaleTimeString() }, ...items].slice(0, 8));
  }, []);

  const checkRuntime = useCallback(async () => {
    try {
      const data = await readApi(await fetch(API_URL("/api/health"), { cache: "no-store" }));
      const ready = Boolean(data.ok && data.agent === "Guri");
      setApiReady(ready);
      if (ready) {
        try {
          const phoneResponse = await fetch(API_URL("/api/mobile/devices"), { cache: "no-store" });
          const phoneData = await readApi(phoneResponse);
          const devices = phoneData.devices || [];
          setPhoneStatus(devices.length ? "Android USB connected" : "Phone connect करें और USB debugging allow करें");
        } catch {
          setPhoneStatus("Android Platform Tools / USB connection जाँचें");
        }
        setStatus("Guri तैयार है — आपका PC connected है");
      } else {
        setStatus("Guri runtime उपलब्ध नहीं है");
      }
      return ready;
    } catch (error) {
      setApiReady(false);
      setStatus(error.message || "Guri backend चालू नहीं है");
      return false;
    }
  }, []);

  useEffect(() => {
    void checkRuntime();
    const timer = window.setInterval(() => {
      if (!listeningRef.current && !workingRef.current) void checkRuntime();
    }, 10000);
    return () => window.clearInterval(timer);
  }, [checkRuntime]);

  useEffect(() => { listeningRef.current = listening; }, [listening]);
  useEffect(() => { workingRef.current = working; }, [working]);

  const speak = useCallback((text) => new Promise((resolve) => {
    if (!("speechSynthesis" in window) || !text) return resolve();
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "hi-IN";
    utterance.onend = resolve;
    utterance.onerror = resolve;
    window.speechSynthesis.speak(utterance);
    window.setTimeout(resolve, 12000);
  }), []);

  const runCommand = useCallback(async (spoken) => {
    if (workingRef.current) return;
    const command = cleanCommand(spoken);
    if (!command) return;
    workingRef.current = true;
    setWorking(true);
    setStatus("आपकी command execute हो रही है…");
    addEvent("आपने कहा", command, "info");
    try {
      const response = await fetch(API_URL("/api/chat"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: command }),
      });
      const data = await readApi(response);
      const answer = data.reply || "Command पूरी हुई।";
      setReply(answer);
      setStatus(data.status === "partial" ? "काम आंशिक रूप से हुआ" : "Command execute हुई");
      addEvent(data.action || "Action", answer, data.status === "failed" ? "error" : "success");
      await speak(answer);
    } catch (error) {
      const answer = "काम नहीं हो पाया: " + (error.message || error);
      setReply(answer);
      setStatus("Command execute नहीं हुई");
      addEvent("Execution error", answer, "error");
      await speak(answer);
    } finally {
      workingRef.current = false;
      setWorking(false);
      if (listeningRef.current && document.visibilityState === "visible") {
        restartRef.current = window.setTimeout(startListening, 650);
      }
    }
  }, [addEvent, speak]);

  const startListening = useCallback(() => {
    if (!listeningRef.current || workingRef.current || document.visibilityState !== "visible") return;
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setStatus("Voice recognition के लिए Chrome browser इस्तेमाल करें।");
      listeningRef.current = false;
      setListening(false);
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
        if (phrase) {
          setHeard(phrase);
          void runCommand(phrase);
        }
      };
      recognition.onerror = (event) => {
        if (event.error === "not-allowed" || event.error === "service-not-allowed") {
          setStatus("Microphone permission allow करें");
          listeningRef.current = false;
          setListening(false);
        } else if (event.error !== "no-speech" && event.error !== "aborted") {
          setStatus("Voice error: " + event.error);
        }
      };
      recognition.onend = () => {
        recognitionRef.current = null;
        if (listeningRef.current && !workingRef.current && document.visibilityState === "visible") {
          restartRef.current = window.setTimeout(startListening, 500);
        }
      };
      recognition.start();
    } catch (error) {
      setStatus("Microphone शुरू नहीं हुआ: " + error.message);
      if (listeningRef.current) restartRef.current = window.setTimeout(startListening, 1000);
    }
  }, [runCommand]);

  useEffect(() => {
    const visibility = () => {
      if (document.visibilityState === "visible" && listeningRef.current && !workingRef.current) startListening();
      else if (document.visibilityState !== "visible") {
        try { recognitionRef.current?.stop(); } catch {}
      }
    };
    document.addEventListener("visibilitychange", visibility);
    return () => {
      document.removeEventListener("visibilitychange", visibility);
      window.clearTimeout(restartRef.current);
      try { recognitionRef.current?.stop(); } catch {}
      window.speechSynthesis?.cancel();
    };
  }, [startListening]);

  function toggleListening() {
    if (listeningRef.current) {
      listeningRef.current = false;
      setListening(false);
      window.clearTimeout(restartRef.current);
      try { recognitionRef.current?.stop(); } catch {}
      setStatus("Guri pause है");
      return;
    }
    if (!apiReady) {
      setStatus("पहले Start-Guri.bat चलाकर local runtime start करें");
      return;
    }
    listeningRef.current = true;
    setListening(true);
    setStatus("Microphone चालू हो रहा है…");
    startListening();
  }

  return (
    <main className="guri-shell">
      <div className={"guri-orb" + (listening ? " listening" : "") + (working ? " working" : "")}>
        {working ? <LoaderCircle className="spin" size={54} /> : <Bot size={58} />}
      </div>
      <h1>Guri</h1>
      <p className="guri-status">{status}</p>
      <p className="guri-hint">बस बोलिए — Guri आपके PC पर काम करेगी</p>
      <button className={"guri-mic" + (listening ? " active" : "")} onClick={toggleListening} disabled={working || !apiReady}>
        {listening ? <MicOff size={23} /> : <Mic size={23} />}
        {listening ? "सुनना बंद करें" : "Guri को सुनाएँ"}
      </button>
      <section className="guri-runtime">
        <div className="runtime-heading"><Terminal size={16}/> GURI LOCAL RUNTIME <span className={apiReady ? "runtime-online" : "runtime-offline"}><Wifi size={13}/> {apiReady ? "Connected" : "Offline"}</span></div>
        <p><Monitor size={14}/> Windows automation runtime</p>
        <p><Smartphone size={14}/> Android phone: {phoneStatus}</p>
        {!apiReady && <div className="runtime-help">Windows पर <b>Start-Guri.bat</b> पर double-click करें। Guri backend और voice screen अपने आप start होंगे।</div>}
      </section>
      {heard && <section className="guri-message"><small>आपने कहा</small><p>{heard}</p></section>}
      <section className="guri-message response"><small>GURI RESPONSE</small><p>{reply}</p></section>
      <section className="guri-terminal">
        <div className="runtime-heading"><Terminal size={15}/> ACTIVITY TERMINAL <span>LIVE LOG</span></div>
        {events.length === 0 ? <p className="terminal-empty">अभी कोई command execute नहीं हुई।</p> : events.map((event) => (
          <div className="terminal-event" key={event.id}>
            <span className="terminal-time">{event.time}</span>
            <span className={"terminal-dot " + event.state}/>
            <div><b>{event.label}</b><p>{event.detail}</p></div>
          </div>
        ))}
      </section>
      <p className="guri-footnote">Voice commands आपके अपने PC पर चल रहे local Guri runtime को भेजे जाते हैं। कोई Render URL या pairing link डालने की जरूरत नहीं है।</p>
    </main>
  );
}
