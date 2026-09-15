import asyncio
import edge_tts
from aiohttp import web
import base64
import tempfile
import os

VOICES_MAP = {
    "ja": "ja-JP-NanamiNeural",
    "ja-jp": "ja-JP-NanamiNeural",
    "vi": "vi-VN-HoaiMyNeural",
    "vi-vn": "vi-VN-HoaiMyNeural",
    "en": "en-US-JennyNeural",
    "en-us": "en-US-JennyNeural",
    "ko": "ko-KR-SunHiNeural",
    "ko-kr": "ko-KR-SunHiNeural",
    "zh": "zh-CN-XiaoxiaoNeural",
    "zh-cn": "zh-CN-XiaoxiaoNeural",
    "fr": "fr-FR-DeniseNeural",
    "fr-fr": "fr-FR-DeniseNeural",
    "es": "es-ES-ElviraNeural",
    "es-es": "es-ES-ElviraNeural"
}

TTS_CACHE = {}

def resolve_voice(language: str) -> str:
    lang = (language or "ja").strip().lower()
    if lang in VOICES_MAP:
        return VOICES_MAP[lang]
    prefix = lang[:2]
    return VOICES_MAP.get(prefix, "ja-JP-NanamiNeural")

async def handle_tts(request: web.Request) -> web.Response:
    try:
        data = await request.json()
        text = (data.get("text") or "").strip()
        language = (data.get("language") or "ja").strip()

        if not text:
            return web.json_response({
                "status": "success",
                "voice_used": "none",
                "audio_base64": ""
            }, headers={"Access-Control-Allow-Origin": "*"})

        voice = resolve_voice(language)
        cache_key = f"{voice}:{text}"

        # 1. Trả về ngay lập tức nếu đã có trong Cache RAM (0.1ms)
        if cache_key in TTS_CACHE:
            return web.json_response({
                "status": "success",
                "voice_used": voice,
                "audio_base64": TTS_CACHE[cache_key],
                "cached": True
            }, headers={"Access-Control-Allow-Origin": "*"})

        # 2. Thu thập dữ liệu âm thanh trực tiếp trong bộ nhớ RAM qua Stream (Không ghi đĩa)
        communicate = edge_tts.Communicate(text, voice)
        audio_bytes = bytearray()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_bytes.extend(chunk["data"])

        audio_base64 = base64.b64encode(audio_bytes).decode("utf-8")

        if len(TTS_CACHE) > 2000:
            TTS_CACHE.clear()
        TTS_CACHE[cache_key] = audio_base64

        return web.json_response({
            "status": "success",
            "voice_used": voice,
            "audio_base64": audio_base64,
            "cached": False
        }, headers={"Access-Control-Allow-Origin": "*"})
    except Exception as e:
        return web.json_response({
            "status": "error",
            "message": str(e)
        }, status=500, headers={"Access-Control-Allow-Origin": "*"})

async def handle_options(request: web.Request) -> web.Response:
    return web.Response(headers={
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type"
    })

app = web.Application()
app.router.add_options('/tts', handle_options)
app.router.add_post('/tts', handle_tts)

if __name__ == '__main__':
    web.run_app(app, host='127.0.0.1', port=5000)
