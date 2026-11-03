import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, MicOff, Bot, LoaderCircle, Link2, Smartphone, Laptop } from "lucide-react";
import "./style.css";

const API = import.meta.env.VITE_API_URL || (
  ["localhost", "127.0.0.1"].includes(window.location.hostname)
    ? "http://127.0.0.1:8000"
    : window.location.origin
);

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
  return (phrase || "").replace(/^\s*(?:गुरी|guri)\s*[,،:;-]?\s*/i, "").trim();
}

export default function App() {
  const [active, setActive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("Guri Cloud जाँच रही है…");
  const [heard, setHeard] = useState("");
  const [reply, setReply] = useState("माइक दबाएँ और सीधे आदेश बोलें।");
  const [cloudMode, setCloudMode] = useState(false);
  const [pairingCode, setPairingCode] = useState(() => sessionStorage.getItem("guri_pairing_code") || "");
  const [pairingVerified, setPairingVerified] = useState(false);
  const [devices, setDevices] = useState({ windows: "offline", android: "offline" });
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

  const refreshDevices = useCallback(async (code = pairingCode) => {
    if (!code) return;
    try {
      const response = await fetch(API + "/api/devices", {
        headers: { "X-Guri-Pairing-Code": code },
        cache: "no-store",
      });
      const data = await parseResponse(response);
      setDevices(data.devices || { windows: "offline", android: "offline" });
      setPairingVerified(true);
    } catch (error) {
      setPairingVerified(false);
      setDevices({ windows: "offline", android: "offline" });
      throw error;
    }
  }, [pairingCode]);

  const checkBackend = useCallback(async () => {
    try {
      const response = await fetch(API + "/api/health", { cache: "no-store" });
      const data = await parseResponse(response);
      if (!data.ok || data.agent !== "Guri") throw new Error("Guri Cloud health check failed.");
      setCloudMode(Boolean(data.cloud_mode));
      if (data.cloud_mode) {
        setStatus("Guri Cloud connected");
        if (pairingCode) {
          try {
            await refreshDevices(pairingCode);
          } catch {
            setStatus("Pairing code जाँचें");
          }
        }
      } else {
        setPairingVerified(true);
        setStatus("Guri service connected");
      }
      return data;
    } catch (error) {
      setStatus("Guri Cloud connection failed: " + (error?.message || error));
      return null;
    }
  }, [pairingCode, refreshDevices]);

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

  async function connectPairingCode() {
    const code = pairingCode.trim();
    if (!code) {
      setStatus("Render pairing code डालें");
      return;
    }
    setBusy(true);
    try {
      const response = await fetch(API + "/api/pairing/verify", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code }),
      });
      const data = await parseResponse(response);
      sessionStorage.setItem("guri_pairing_code", code);
      await refreshDevices(code);
      setPairingVerified(true);
      setReply("Guri Cloud pair हो गया। अब Windows और Android companion connect करें।");
      setStatus(data.message || "Guri paired");
    } catch (error) {
      sessionStorage.removeItem("guri_pairing_code");
      setPairingVerified(false);
      setStatus("Pairing failed: " + (error?.message || error));
    } finally {
      setBusy(false);
    }
  }

  const runCommand = useCallback(async (spokenText) => {
    if (busyRef.current) return;
    const command = cleanCommand(spokenText) || (spokenText || "").trim();
    if (!command) return;

    if (cloudMode && (!pairingCode || !pairingVerified)) {
      const message = "पहले Render का GURI_PAIRING_CODE डालकर Connect दबाएँ।";
      setReply(message);
      setStatus("Cloud pairing जरूरी है");
      await speakReply(message);
      return;
    }

    busyRef.current = true;
    setBusy(true);
    setStatus("Command Guri Cloud को भेज रही हूँ…");

    try {
      const response = await fetch(API + "/api/chat", {
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
        try { await refreshDevices(); } catch {}
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
  }, [cloudMode, pairingCode, pairingVerified, refreshDevices, speakReply]);

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

      {cloudMode && (
        <section className="guri-message pairing">
          <small><Link2 size={13} /> SECURE DEVICE PAIRING</small>
          <p>Render Dashboard से GURI_PAIRING_CODE डालें। यही code Windows और Android companions में भी डालना है।</p>
          <div className="guri-pair-row">
            <input
              type="password"
              value={pairingCode}
              onChange={(event) => {
                setPairingCode(event.target.value);
                setPairingVerified(false);
              }}
              placeholder="GURI_PAIRING_CODE"
              autoComplete="off"
            />
            <button onClick={connectPairingCode} disabled={busy || !pairingCode.trim()}>Connect</button>
          </div>
          {pairingVerified && (
            <div className="guri-devices">
              <span><Laptop size={14}/> Windows: {devices.windows}</span>
              <span><Smartphone size={14}/> Android: {devices.android}</span>
            </div>
          )}
        </section>
      )}

      {heard && <section className="guri-message"><small>आपने कहा</small><p>{heard}</p></section>}
      <section className="guri-message response"><small>Guri</small><p>{reply}</p></section>
      <p className="guri-footnote">Guri Cloud commands paired devices को इंटरनेट से भेजती है। Device पर companion agent चालू होना जरूरी है।</p>
    </main>
  );
}
