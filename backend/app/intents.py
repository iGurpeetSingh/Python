import re
from dataclasses import dataclass


@dataclass(frozen=True)
class ChatGPTCommand:
    message: str | None
    search_first: bool = False


def _extract_message(value: str) -> str | None:
    markers = (
        r"\bmessage\b",
        r"\bmsg\b",
        r"मैसेज",
        r"मेसेज",
        r"संदेश",
        r"\bsend\b",
        r"भेजो",
        r"लिखो",
    )
    match = re.search("|".join(markers), value, flags=re.IGNORECASE)
    if not match:
        return None

    tail = value[match.end():].strip(" \t\r\n,:;-")
    # Strip command glue, not the actual message content.
    prefixes = (
        r"^(?:to\s+(?:it|chatgpt)|it|chatgpt|उसको|इसको|उसमें|उसे|करो|करके|भेजो|भेज देना|लिखो|कि|that|saying)\b",
    )
    changed = True
    while changed and tail:
        changed = False
        for pattern in prefixes:
            cleaned = re.sub(pattern, "", tail, flags=re.IGNORECASE).strip(" \t\r\n,:;-")
            if cleaned != tail:
                tail = cleaned
                changed = True

    tail = re.sub(
        r"[,،\s]+(?:बस|please|that's all|और भेज दो|भेज देना)\s*[.!?।]*$",
        "",
        tail,
        flags=re.IGNORECASE,
    ).strip(" \t\r\n,:;-")
    tail = tail.strip("\"'“”‘’ ")
    return tail or None


def parse_chatgpt_command(text: str) -> ChatGPTCommand | None:
    """
    Parse spoken instructions such as:
    - New Tab खोलो, ChatGPT search करो और उसको message Hi.
    - Open ChatGPT in a new tab and message it saying Hello.
    """
    value = re.sub(r"\s+", " ", (text or "")).strip()
    low = value.casefold()
    if not re.search(r"chat\s*gpt|chatgpt", low):
        return None

    open_requested = bool(re.search(
        r"\b(open|launch|new tab|browser|search|find)\b|ओपन|खोल|नया टैब|न्यू टैब|सर्च|खोज",
        low,
        flags=re.IGNORECASE,
    ))
    send_requested = bool(re.search(
        r"\b(message|msg|send|text|tell)\b|मैसेज|मेसेज|संदेश|भेजो|लिखो|बोलो",
        low,
        flags=re.IGNORECASE,
    ))

    if not (open_requested or send_requested):
        return None

    message = _extract_message(value) if send_requested else None

    if send_requested and not message:
        if re.search(r"क्या\s+काम\s+कर\s+रहे\s+हो", value):
            message = "क्या काम कर रहे हो?"
        elif re.search(r"\bwhat\s+are\s+you\s+working\s+on\b", low):
            message = "What are you working on?"

    search_first = bool(re.search(
        r"\b(search|google|find)\b|सर्च|खोज",
        low,
        flags=re.IGNORECASE,
    ))
    return ChatGPTCommand(message=message, search_first=search_first)
