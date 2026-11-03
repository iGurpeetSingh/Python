import re
from dataclasses import dataclass


@dataclass(frozen=True)
class ChatGPTCommand:
    message: str | None


def parse_chatgpt_command(text: str) -> ChatGPTCommand | None:
    """Recognise explicit requests to open ChatGPT and/or send it a message."""
    value = (text or "").strip()
    low = value.casefold()
    if not re.search(r"chat\s*gpt|chatgpt", low):
        return None

    open_requested = bool(re.search(
        r"\b(open|launch|new tab|browser|ओपन|खोल|खोलो|नया टैब|न्यू टैब)\b",
        low,
    ))
    send_requested = bool(re.search(
        r"\b(message|send|ask|text|मैसेज|संदेश|पूछो|लिखो)\b",
        low,
    )) or "मैसेज" in value or "संदेश" in value

    if not (open_requested or send_requested):
        return None

    message = None
    if send_requested:
        match = re.search(r"(?:कि|that|saying|कहकर)\s+(.+)$", value, flags=re.IGNORECASE)
        if match:
            message = match.group(1).strip()
            message = re.sub(r"[,،\s]+(?:बस|please|that's all)\s*[.!?।]*$", "", message, flags=re.IGNORECASE).strip()
            message = message.strip("\"'“”‘’ ")
        if not message and re.search(r"क्या\s+काम\s+कर\s+रहे\s+हो", value):
            message = "क्या काम कर रहे हो?"
        if not message and re.search(r"what\s+are\s+you\s+working\s+on", low):
            message = "What are you working on?"

    return ChatGPTCommand(message=message)
