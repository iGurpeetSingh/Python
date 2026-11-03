from contextlib import asynccontextmanager
import re
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel,Field
from .config import FRONTEND_ORIGIN
from .core import init_db,save,history,local_answer,open_site,search_web,instagram_open,reels_scroll,adb

class Chat(BaseModel):
    message:str=Field(min_length=1,max_length=2000)
class Scroll(BaseModel):
    swipes:int=Field(default=3,ge=1,le=30)

@asynccontextmanager
async def lifespan(app):
    init_db()
    yield
app=FastAPI(title="Orbit Personal Agent",lifespan=lifespan)
app.add_middleware(CORSMiddleware,allow_origins=[FRONTEND_ORIGIN,"http://127.0.0.1:5173"],allow_methods=["GET","POST"],allow_headers=["*"])

@app.get("/api/health")
async def health(): return {"ok":True,"agent":"Orbit"}
@app.get("/api/history")
async def get_history(): return history()
@app.get("/api/mobile/devices")
async def mobile_devices():
    try:
        out=await adb("devices")
        return {"devices":[x.split()[0] for x in out.splitlines()[1:] if "\\tdevice" in x]}
    except HTTPException: return {"devices":[]}
@app.post("/api/mobile/instagram/open")
async def open_instagram(): return {"reply":await instagram_open()}
@app.post("/api/mobile/instagram/reels/scroll")
async def scroll(body:Scroll): return {"reply":await reels_scroll(body.swipes)}

@app.post("/api/chat")
async def chat(body:Chat):
    msg=body.message.strip()
    save("user",msg)
    low=msg.lower()
    try:
        if any(x in low for x in ["open instagram","instagram kholo","इंस्टाग्राम खोल","इंस्टाग्राम ओपन"]):
            result={"reply":await instagram_open(),"action":"instagram_open"}
        elif any(x in low for x in ["scroll reels","reels scroll","रील स्क्रॉल","रील्स स्क्रॉल"]):
            m=re.search(r"(\\d+)",low); n=max(1,min(30,int(m.group(1)))) if m else 3
            result={"reply":await reels_scroll(n),"action":"reels_scroll"}
        elif any(x in low for x in ["open amazon","amazon kholo","अमेज़न खोल"]):
            result={"reply":await open_site("https://www.amazon.in"),"action":"open"}
        elif any(x in low for x in ["open google","google kholo","गूगल खोल"]):
            result={"reply":await open_site("https://www.google.com"),"action":"open"}
        elif any(x in low for x in ["open youtube","youtube kholo","यूट्यूब खोल"]):
            result={"reply":await open_site("https://www.youtube.com"),"action":"open"}
        elif "amazon" in low and any(x in low for x in ["search","find","watch","watches","घड़ी","खोज","सर्च"]):
            q=re.sub(r".*?(?:search|find|खोजो|सर्च करो)\\s*(?:for)?\\s*","",msg,flags=re.I).strip() or "watches"
            rows=await search_web(q,True)
            result={"reply":"Amazon results sorted by displayed price where available. Verify price on the product page.","action":"amazon_search","results":rows,"status":"completed" if rows else "partial"}
        elif any(x in low for x in ["google पर सर्च","search google for","गूगल पर खोज"]):
            q=re.sub(r".*?(?:for|खोजो|सर्च करो)\\s*","",msg,flags=re.I)
            rows=await search_web(q)
            result={"reply":"\\n".join(x["title"]+"\\n"+x["snippet"]+"\\n"+x["url"] for x in rows) or "No readable results.","action":"google_search","results":rows}
        else:
            answer=await local_answer(msg)
            result={"reply":answer or "Local AI model is not available. Install Ollama and run: ollama pull qwen2.5:3b","action":"local_ai","status":"completed" if answer else "needs_setup"}
    except HTTPException: raise
    except Exception as e: raise HTTPException(500,f"Task failed: {type(e).__name__}: {e}")
    save("assistant",result["reply"])
    return result
