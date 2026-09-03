import asyncio
import edge_tts
from aiohttp import web
import json
import base64
import tempfile
import os

async def handle_tts(request):
    try:
        data = await request.json()
        text = data.get("text", "")
        lang = data.get("language", "ja")
        
        # Chọn giọng đọc phù hợp
        voice = "ja-JP-NanamiNeural" if lang == "ja" else "en-US-JennyNeural"
        
        # Lưu file tạm
        fd, path = tempfile.mkstemp(suffix=".mp3")
        os.close(fd)
        
        communicate = edge_tts.Communicate(text, voice)
        await communicate.save(path)
        
        # Đọc file tạm và chuyển thành base64
        with open(path, "rb") as f:
            audio_bytes = f.read()
        audio_base64 = base64.b64encode(audio_bytes).decode('utf-8')
        
        # Xóa file tạm
        os.remove(path)
        
        return web.json_response({
            "status": "success",
            "audio_base64": audio_base64
        })
    except Exception as e:
        return web.json_response({"status": "error", "message": str(e)}, status=500)

app = web.Application()
app.router.add_post('/tts', handle_tts)

if __name__ == '__main__':
    web.run_app(app, host='127.0.0.1', port=5000)
