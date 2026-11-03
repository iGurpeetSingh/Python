import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, MicOff, Bot, LoaderCircle, Link2, Smartphone, Laptop, Server } from "lucide-react";
import "./style.css";

const IS_LOCAL_PAGE = ["localhost", "127.0.0.1"].includes(window.location.hostname);
const DEFAULT_API_BASE = import.meta.env.VITE_API_URL || (
  IS_LOCAL_PAGE ? "http://127.0.0.1:8000" : ""
);

function normalizeBase(value) {
  return (value || "").trim().replace(/\/+$/, "");
}

async function parseResponse(response) {
  const raw = await response.text();
  let data;
  try {
    data = raw ? JSON.parse(raw) : {};
  } catch {
    data = { detail: raw || ("HTTP " + response.status) };
  }
  if (!response.ok) {
    const detail = data.detail || data.message || ("HTTP " + response.status);
    if (response.status === 404 && /vercel\.app$/i.test(new URL(response.url).hostname)) {
      throw new Error("यह Vercel frontend है; यहाँ /api/chat backend मौजूद नहीं है। Cloud API URL में अपना Render service address डालें।");
    }
    throw new Error(detail);
  }
  return data;
}

function cleanCommand(phrase) {
  return (phrase || "").replace(/^\s*(?:गुरी|guri)\s*[,،:;-]?\s*/i, "").trim();
}

export default function App() {
  const [active, setActive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("Guri Cloud जाँच रही है…");
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("पहले Guri Cloud को connect करें, फिर माइक से आदेश दें।");
  const [cloudMode, setCloudMode] = useState(!IS_LOCAL_PAGE);
  const [cloudUrl, setCloudUrl] = useState(() =>
    localStorage.getItem("guri_cloud_url") || DEFAULT_API_BASE
  );
  const [pairingCode, setPairingCode] = useState(() =>
    sessionStorage.getItem("guri_pairing_code") || ""
  );
  const [pairingVerified, setPairingVerified] = useState(false);
  const [devices, setDevices] = useState({ windows: "offline", android: "offline" });
  const recognitionRef = useRef(null);
  const activeRef = useRef(false);
  const busyRef = useRef(false);
  const restartTimer = useRef(null);

  const apiBase = normalizeBase(cloudUrl);

  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  const requestHealth = useCallback(async (base) => {
    if (!base) {
      throw new Error(
        "Vercel पर voice website host है, Python API नहीं। Render पर deploy किए गए Guri Cloud का URL यहाँ डालें।"
      );
    }
    const response = await fetch(base + "/api/health", { cache: "no-store" });
    return parseResponse(response);
  }, []);

  const refreshDevices = useCallback(async (code = pairingCode, base = apiBase) => {
    if (!code || !base) return;
    const response = await fetch(base + "/api/devices", {
      headers: { "X-Guri-Pairing-Code": code },
      cache: "no-store",
    });
    const data = await parseResponse(response);
    setDevices(data.devices || { windows: "offline", android: "offline" });
    setPairingVerified(true);
    return data;
  }, [apiBase, pairingCode]);

  const checkBackend = useCallback(async () => {
    if (!apiBase) {
      setCloudMode(true);
      setPairingVerified(false);
      setStatus("Vercel पर frontend है — Render Cloud URL जोड़ें");
      return null;
    }
    try {
      const data = await requestHealth(apiBase);
      if (!data.ok || data.agent !== "Guri") {
        throw new Error("इस URL पर Guri API नहीं मिली। Render service URL जाँचें।");
      }
      setCloudMode(Boolean(data.cloud_mode));
      if (data.cloud_mode) {
        setStatus("Guri Cloud connected — pairing करें");
        if (pairingCode) {
          try {
            await refreshDevices(pairingCode, apiBase);
            setStatus("Guri Cloud connected");
          } catch {
            setPairingVerified(false);
            setStatus("Pairing code जाँचें");
          }
        }
      } else {
        setPairingVerified(true);
        setStatus("Guri local service connected");
      }
      return data;
    } catch (error) {
      setPairingVerified(false);
      setStatus("Cloud connection नहीं हुई: " + (error?.message || error));
      return null;
    }
  }, [apiBase, pairingCode, refreshDevices, requestHealth]);

  useEffect(() => {
    void checkBackend();
    const interval = window.setInterval(() => {
      if (!activeRef.current && !busyRef.current) void checkBackend();
    }, 15000);
    return () => window.clearInterval(interval);
  }, [checkBackend]);

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

  async function connectCloud() {
    const base = normalizeBase(cloudUrl);
    if (!/^https?:\/\//i.test(base)) {
      setStatus("Cloud URL https:// से शुरू होना चाहिए।");
      return;
    }
    setBusy(true);
    setStatus("Guri Cloud health check कर रही हूँ…");
    try {
      const health = await requestHealth(base);
      if (!health.ok || health.agent !== "Guri") {
        throw new Error("इस URL पर Guri API नहीं मिली। Render service URL कॉपी करें।");
      }

      localStorage.setItem("guri_cloud_url", base);
      setCloudMode(Boolean(health.cloud_mode));

      if (health.cloud_mode) {
        const code = pairingCode.trim();
        if (!code) {
          setPairingVerified(false);
          setStatus("Cloud चालू है। अब Render का GURI_PAIRING_CODE डालकर Connect करें।");
          setReply("Backend मिल गया। अब Render pairing code डालें।");
          return;
        }
        const verifyResponse = await fetch(base + "/api/pairing/verify", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code }),
        });
        const verified = await parseResponse(verifyResponse);
        sessionStorage.setItem("guri_pairing_code", code);
        await refreshDevices(code, base);
        setPairingVerified(true);
        setReply("Guri Cloud pair हो गया। अब Windows और Android companion connect करें।");
        setStatus(verified.message || "Guri Cloud paired");
      } else {
        setPairingVerified(true);
        setStatus("Guri API connected");
        setReply("Guri backend से connection सफल है।");
      }
    } catch (error) {
      setPairingVerified(false);
      setStatus("Connection failed: " + (error?.message || error));
      setReply("काम शुरू नहीं हुआ। Cloud URL और deployment health जाँचें।");
    } finally {
      setBusy(false);
    }
  }

  async function connectPairingCode() {
    await connectCloud();
  }

  const runCommand = useCallback(async (spokenText) => {
    if (busyRef.current) return;
    const command = cleanCommand(spokenText) || (spokenText || "").trim();
    if (!command) return;

    if (!apiBase) {
      const message = "पहले Render पर Guri Cloud deploy करें और उसका URL डालकर Connect करें।";
      setReply(message);
      setStatus("Cloud URL missing है");
      await speakReply(message);
      return;
    }

    if (cloudMode && (!pairingCode || !pairingVerified)) {
      const message = "पहले Guri Cloud URL और pairing code से Connect करें।";
      setReply(message);
      setStatus("Cloud pairing जरूरी है");
      await speakReply(message);
      return;
    }

    busyRef.current = true;
    setBusy(true);
    setStatus("Command Guri Cloud को भेज रही हूँ…");

    try {
      const response = await fetch(apiBase + "/api/chat", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(cloudMode ? { "X-Guri-Pairing-Code": pairingCode } : {}),
        },
        body: JSON.stringify({ message: command }),
      });
      const data = await parseResponse(response);
      const answer = data.reply || "काम पूरा हुआ।";
      setReply(answer);
      setStatus(
        data.status === "partial"
          ? "काम आंशिक रूप से हुआ"
          : (data.device ? data.device + " device पर command पूरी" : "काम पूरा हुआ")
      );
      await speakReply(answer);
      if (cloudMode) {
        try { await refreshDevices(pairingCode, apiBase); } catch {}
      }
    } catch (error) {
      const message = error?.message || String(error);
      const answer = "काम नहीं हो पाया: " + message;
      setReply(answer);
      setStatus("Command execute नहीं हुई");
      await speakReply(answer);
    } finally {
      busyRef.current = false;
      setBusy(false);
      if (activeRef.current && document.visibilityState === "visible") {
        restartTimer.current = window.setTimeout(startRecognition, 700);
      }
    }
  }, [apiBase, cloudMode, pairingCode, pairingVerified, refreshDevices, speakReply]);

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
      if (activeRef.current) restartTimer.current = window.setTimeout(startRecognition, 1000);
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
      <p className="guri-hint">माइक दबाएँ और सीधे आदेश बोलें</p>
      <button className={"guri-mic" + (active ? " active" : "")} onClick={toggleListening} disabled={busy}>
        {active ? <MicOff size={23} /> : <Mic size={23} />}
        {active ? "सुनना बंद करें" : "Guri को सुनाएँ"}
      </button>

      <section className="guri-message pairing">
        <small><Server size={13} /> GURI CLOUD CONNECTION</small>
        <p>आपकी Vercel website केवल voice screen है। यहाँ Render पर deploy किए गए Guri backend का URL डालें।</p>
        <div className="guri-pair-row">
          <input
            type="url"
            value={cloudUrl}
            onChange={(event) => {
              setCloudUrl(event.target.value);
              setPairingVerified(false);
            }}
            placeholder="https://your-guri-cloud.onrender.com"
            autoComplete="url"
            spellCheck={false}
          />
        </div>
        <div className="guri-pair-row">
          <input
            type="password"
            value={pairingCode}
            onChange={(event) => {
              setPairingCode(event.target.value);
              setPairingVerified(false);
            }}
            placeholder="Render GURI_PAIRING_CODE"
            autoComplete="off"
          />
          <button onClick={connectPairingCode} disabled={busy || !apiBase}>
            {busy ? "Checking…" : "Connect"}
          </button>
        </div>
        {pairingVerified && (
          <div className="guri-devices">
            <span><Laptop size={14}/> Windows: {devices.windows}</span>
            <span><Smartphone size={14}/> Android: {devices.android}</span>
          </div>
        )}
      </section>

      {heard && <section className="guri-message"><small>आपने कहा</small><p>{heard}</p></section>}
      <section className="guri-message response"><small>Guri</small><p>{reply}</p></section>
      <p className="guri-footnote">
        Guri Cloud commands paired devices को इंटरनेट से भेजती है। Device पर companion agent चालू होना जरूरी है।
      </p>
    </main>
  );
}
