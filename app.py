# 1. Imports (all at top)
import asyncio
import io
import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

import fitz
import redis
import requests
import uvicorn
from crewai import Crew
from docx import Document as DocxDocument
from fastapi import FastAPI, File, Header, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from pythonjsonlogger import jsonlogger
from sentence_transformers import CrossEncoder

from db import close_connection, create_connection, execute_query, get_schema_info
from extract_data import extract_details
from system_prompt import run_mysql_query_agent

# 2. Environment variable loading
from dotenv import load_dotenv

load_dotenv()

os.environ.setdefault("OPENAI_API_KEY", "DUMMY_KEY")
os.environ.setdefault("OPENAI_MODEL", "text-dummy-001")
Crew.global_llm = "ollama/qwen3:8b"

SERVICE_NAME = "python-ai-service"
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
APP_HOST = os.getenv("APP_HOST", "0.0.0.0")
APP_PORT = int(os.getenv("APP_PORT", "8000"))
INTERNAL_ONLY = os.getenv("INTERNAL_ONLY", "false").lower() == "true"
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "")
DEFAULT_EMBED_MODEL = os.getenv("EMBED_MODEL", "nomic-embed-text")

# 3. Logger setup (JSON format)
class DefaultLogFieldsFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "service_name"):
            record.service_name = SERVICE_NAME
        if not hasattr(record, "endpoint"):
            record.endpoint = ""
        if not hasattr(record, "user_uuid"):
            record.user_uuid = ""
        return True


logger = logging.getLogger(SERVICE_NAME)
logger.setLevel(logging.INFO)
logger.handlers = []
stream_handler = logging.StreamHandler()
stream_handler.addFilter(DefaultLogFieldsFilter())
formatter = jsonlogger.JsonFormatter(
    "%(asctime)s %(levelname)s %(service_name)s %(endpoint)s %(user_uuid)s %(message)s"
)
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)
logger.propagate = False

# Global runtime state
cached_details: List[Dict[str, Any]] = []
rerank_model: Optional[CrossEncoder] = None


async def _warm_ollama_embed() -> bool:
    try:
        payload = {"model": DEFAULT_EMBED_MODEL, "prompt": "warmup"}
        response = requests.post(
            f"{OLLAMA_BASE_URL}/api/embeddings",
            json=payload,
            timeout=10,
        )
        response.raise_for_status()
        return True
    except Exception:
        return False


def _reload_cached_details() -> int:
    global cached_details
    details: List[Dict[str, Any]] = []
    details.extend(extract_details("CTM_table.xlsx") or [])
    details.extend(extract_details("inventory_table_details.xlsx") or [])
    cached_details = details
    return len(cached_details)


# 6. Lifespan function (startup events)
@asynccontextmanager
async def lifespan(app: FastAPI):
    global rerank_model
    logger.info(
        "Python AI service starting",
        extra={"endpoint": "startup", "user_uuid": "", "models_loaded": False},
    )

    loaded_rerank = False
    try:
        rerank_model = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")
        loaded_rerank = True
    except Exception as e:
        logger.error(
            "Failed to load rerank model",
            extra={"endpoint": "startup", "user_uuid": "", "error": str(e)},
        )

    try:
        total = _reload_cached_details()
        logger.info(
            f"Cached {total} table detail records",
            extra={"endpoint": "startup", "user_uuid": ""},
        )
    except Exception as e:
        logger.error(
            "Failed to cache table details",
            extra={"endpoint": "startup", "user_uuid": "", "error": str(e)},
        )

    warm_ok = await _warm_ollama_embed()
    logger.info(
        "Startup completed",
        extra={
            "endpoint": "startup",
            "user_uuid": "",
            "models_loaded": loaded_rerank and warm_ok,
        },
    )

    yield

    logger.info(
        "Python AI service shutting down",
        extra={"endpoint": "shutdown", "user_uuid": ""},
    )


# 4. FastAPI app creation
app = FastAPI(lifespan=lifespan)

# 5. CORS middleware
allowed_origins = [
    origin.strip()
    for origin in os.getenv(
        "CORS_ALLOW_ORIGINS",
        "http://localhost,http://127.0.0.1",
    ).split(",")
    if origin.strip()
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def request_logging_and_internal_guard(request: Request, call_next):
    endpoint = request.url.path
    user_uuid = request.headers.get("x-user-uuid", "")

    if INTERNAL_ONLY:
        client_host = request.client.host if request.client else ""
        if client_host not in {"127.0.0.1", "::1", "localhost"}:
            return JSONResponse(
                status_code=403,
                content={"error": "Access denied", "code": "FORBIDDEN"},
            )

    start = time.perf_counter()
    logger.info(
        "Request received",
        extra={"endpoint": endpoint, "user_uuid": user_uuid},
    )

    try:
        response = await call_next(request)
        duration_ms = round((time.perf_counter() - start) * 1000, 2)
        logger.info(
            "Request completed",
            extra={
                "endpoint": endpoint,
                "user_uuid": user_uuid,
                "duration_ms": duration_ms,
                "success": response.status_code < 400,
            },
        )
        return response
    except Exception as e:
        logger.error(
            f"Error in {endpoint}: {str(e)}",
            extra={"endpoint": endpoint, "user_uuid": user_uuid},
        )
        return JSONResponse(
            status_code=500,
            content={"error": "Internal server error", "code": "INTERNAL_ERROR"},
        )


# 7. Redis client setup
redis_client = redis.Redis(
    host=REDIS_HOST,
    port=REDIS_PORT,
    db=0,
    decode_responses=True,
    socket_connect_timeout=3,
    socket_timeout=3,
)

# 8. Request/Response models (all Pydantic classes)
class PromptRequest(BaseModel):
    prompt: str
    first_request: bool = False
    uuid: Optional[str] = None


class EmbedRequest(BaseModel):
    text: str


class EmbedBatchRequest(BaseModel):
    texts: List[str] = Field(default_factory=list)


class RerankRequest(BaseModel):
    query: str
    documents: List[str]
    top_k: int = 3


class ChunkRequest(BaseModel):
    text: str
    chunk_size: int = 512
    chunk_overlap: int = 50


class ConfidenceRequest(BaseModel):
    answer: str
    retrieved_chunks: List[str]


# 9. Helper functions

def clean_llm_response(response: str) -> str:
    cleaned = response.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.lstrip("`").split("\n", 1)[-1]
    if cleaned.endswith("```"):
        cleaned = cleaned.rstrip("`").rsplit("\n", 1)[0]
    return cleaned.strip()


def error_response(status_code: int, message: str, code: str):
    return JSONResponse(
        status_code=status_code,
        content={"error": message, "code": code},
    )


def get_db_connection():
    conn, cursor = create_connection()
    if conn is None or cursor is None:
        raise HTTPException(status_code=500, detail="Database connection failed")
    return conn, cursor


def _extract_keywords(text: str) -> set:
    return set(re.findall(r"\b[A-Za-z]{4,}\b|\b\d+\b", text.lower()))


def _chunk_text(text: str, chunk_size: int, chunk_overlap: int) -> List[str]:
    if not text or not text.strip():
        return []

    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()]
    if not sentences:
        return []

    chunks_sentence_groups: List[List[str]] = []
    current_group: List[str] = []
    current_words = 0

    for sentence in sentences:
        sentence_words = sentence.split()
        sentence_len = len(sentence_words)

        if current_group and (current_words + sentence_len > chunk_size):
            chunks_sentence_groups.append(current_group)

            overlap_group: List[str] = []
            overlap_words = 0
            for prev_sentence in reversed(current_group):
                overlap_group.insert(0, prev_sentence)
                overlap_words += len(prev_sentence.split())
                if overlap_words >= chunk_overlap:
                    break

            current_group = overlap_group.copy()
            current_words = sum(len(s.split()) for s in current_group)

        current_group.append(sentence)
        current_words += sentence_len

    if current_group:
        chunks_sentence_groups.append(current_group)

    chunks = [" ".join(group).strip() for group in chunks_sentence_groups if group]

    if not chunks:
        return []

    merged_chunks: List[str] = []
    for chunk in chunks:
        chunk_word_count = len(chunk.split())
        if merged_chunks and chunk_word_count < 50:
            merged_chunks[-1] = f"{merged_chunks[-1]} {chunk}".strip()
        else:
            merged_chunks.append(chunk)

    return merged_chunks


def _ollama_embed(text: str, timeout: int = 10) -> List[float]:
    payload = {"model": DEFAULT_EMBED_MODEL, "prompt": text}
    start = time.perf_counter()
    response = requests.post(
        f"{OLLAMA_BASE_URL}/api/embeddings",
        json=payload,
        timeout=timeout,
    )
    response.raise_for_status()
    data = response.json()
    embedding = data.get("embedding") or []
    elapsed = round((time.perf_counter() - start) * 1000, 2)
    logger.info(
        "Ollama call",
        extra={
            "endpoint": "/embed",
            "user_uuid": "",
            "model": DEFAULT_EMBED_MODEL,
            "duration_ms": elapsed,
        },
    )
    return embedding


def _is_admin_request(request: Request, x_admin_token: Optional[str]) -> bool:
    if ADMIN_TOKEN:
        return x_admin_token == ADMIN_TOKEN
    client_host = request.client.host if request.client else ""
    return client_host in {"127.0.0.1", "::1", "localhost"}


# 10. Endpoints in this order
@app.get("/health")
async def health_check():
    try:
        return {"status": "200 OK"}
    except Exception as e:
        logger.error(f"Error in /health: {str(e)}", extra={"endpoint": "/health", "user_uuid": ""})
        return error_response(500, "Internal server error", "INTERNAL_ERROR")


@app.get("/ready")
async def ready_check():
    status = {
        "status": "UP",
        "ollama": "DOWN",
        "redis": "DOWN",
        "mysql": "DOWN",
        "qwen3_loaded": False,
        "cached_details_count": len(cached_details),
    }

    try:
        tags_response = requests.get(f"{OLLAMA_BASE_URL}/api/tags", timeout=3)
        if tags_response.status_code == 200:
            status["ollama"] = "UP"
            data = tags_response.json()
            model_names = [m.get("name", "") for m in data.get("models", [])]
            status["qwen3_loaded"] = any("qwen3:8b" in name for name in model_names)
    except Exception as e:
        logger.error(
            f"Error in /ready (ollama): {str(e)}",
            extra={"endpoint": "/ready", "user_uuid": ""},
        )

    try:
        if redis_client.ping():
            status["redis"] = "UP"
    except Exception as e:
        logger.error(
            f"Error in /ready (redis): {str(e)}",
            extra={"endpoint": "/ready", "user_uuid": ""},
        )

    conn = None
    cursor = None
    try:
        conn, cursor = create_connection()
        if conn and cursor:
            conn.ping(reconnect=True)
            status["mysql"] = "UP"
    except Exception as e:
        logger.error(
            f"Error in /ready (mysql): {str(e)}",
            extra={"endpoint": "/ready", "user_uuid": ""},
        )
    finally:
        close_connection(conn, cursor)

    if "DOWN" in (status["ollama"], status["redis"], status["mysql"]) or not status["qwen3_loaded"]:
        status["status"] = "DOWN"
        return JSONResponse(status_code=503, content=status)
    return JSONResponse(status_code=200, content=status)


@app.post("/embed")
async def embed_text(request: EmbedRequest):
    try:
        if not request.text.strip():
            return error_response(400, "Text must not be empty", "BAD_INPUT")

        embedding = _ollama_embed(request.text, timeout=10)
        logger.info(
            f"Embedded text, dimensions: {len(embedding)}",
            extra={"endpoint": "/embed", "user_uuid": ""},
        )
        return {"embedding": embedding, "dimensions": len(embedding)}
    except Exception as e:
        logger.error(f"Error in /embed: {str(e)}", extra={"endpoint": "/embed", "user_uuid": ""})
        return error_response(500, "Embedding generation failed", "EMBED_FAILED")


@app.post("/embed-batch")
async def embed_batch(request: EmbedBatchRequest):
    try:
        texts = request.texts or []
        results: List[List[float]] = []

        if not texts:
            return {"embeddings": [], "count": 0}

        total_batches = (len(texts) + 9) // 10
        for i in range(0, len(texts), 10):
            batch = texts[i : i + 10]
            logger.info(
                f"Embedding batch {i // 10 + 1}/{total_batches}",
                extra={"endpoint": "/embed-batch", "user_uuid": ""},
            )
            for text in batch:
                try:
                    emb = _ollama_embed(text, timeout=10)
                    results.append(emb)
                except Exception as emb_error:
                    logger.warning(
                        f"Embed failed for one item: {str(emb_error)}",
                        extra={"endpoint": "/embed-batch", "user_uuid": ""},
                    )
                    results.append([])
            await asyncio.sleep(0.1)

        return {"embeddings": results, "count": len(results)}
    except Exception as e:
        logger.error(
            f"Error in /embed-batch: {str(e)}",
            extra={"endpoint": "/embed-batch", "user_uuid": ""},
        )
        return error_response(500, "Batch embedding failed", "EMBED_BATCH_FAILED")


@app.post("/rerank")
async def rerank_documents(request: RerankRequest):
    try:
        if not request.documents:
            return {"results": []}

        top_k = max(1, min(request.top_k, len(request.documents)))

        if rerank_model is None:
            fallback = [
                {"index": i, "score": 0.0, "text": doc}
                for i, doc in enumerate(request.documents[:top_k])
            ]
            return {"results": fallback}

        pairs = [[request.query, doc] for doc in request.documents]

        try:
            scores = rerank_model.predict(pairs)
            scored = [
                {"index": i, "score": float(scores[i]), "text": request.documents[i]}
                for i in range(len(request.documents))
            ]
            scored.sort(key=lambda item: item["score"], reverse=True)
            results = scored[:top_k]
        except Exception:
            results = [
                {"index": i, "score": 0.0, "text": doc}
                for i, doc in enumerate(request.documents[:top_k])
            ]

        logger.info(
            f"Reranked {len(request.documents)} docs, returning top {top_k}",
            extra={"endpoint": "/rerank", "user_uuid": ""},
        )
        return {"results": results}
    except Exception as e:
        logger.error(f"Error in /rerank: {str(e)}", extra={"endpoint": "/rerank", "user_uuid": ""})
        return error_response(500, "Reranking failed", "RERANK_FAILED")


@app.post("/chunk")
async def chunk_text(request: ChunkRequest):
    try:
        if not request.text.strip():
            return {"chunks": [], "count": 0}

        chunk_size = max(request.chunk_size, 50)
        chunk_overlap = max(0, request.chunk_overlap)
        chunks = _chunk_text(request.text, chunk_size, chunk_overlap)
        count = len(chunks)

        logger.info(
            f"Chunked text into {count} chunks",
            extra={"endpoint": "/chunk", "user_uuid": ""},
        )
        return {"chunks": chunks, "count": count}
    except Exception as e:
        logger.error(f"Error in /chunk: {str(e)}", extra={"endpoint": "/chunk", "user_uuid": ""})
        return error_response(500, "Text chunking failed", "CHUNK_FAILED")


@app.post("/confidence")
async def confidence_check(request: ConfidenceRequest):
    try:
        answer_words = _extract_keywords(request.answer)
        if not answer_words:
            return {"score": 0.5, "is_grounded": True, "flag_for_review": False}

        chunk_words = _extract_keywords(" ".join(request.retrieved_chunks or []))
        overlap = len(answer_words & chunk_words)
        score = overlap / max(1, len(answer_words))
        is_grounded = score >= 0.35

        return {
            "score": round(score, 4),
            "is_grounded": is_grounded,
            "flag_for_review": not is_grounded,
        }
    except Exception as e:
        logger.error(
            f"Error in /confidence: {str(e)}",
            extra={"endpoint": "/confidence", "user_uuid": ""},
        )
        return {"score": 0.5, "is_grounded": True, "flag_for_review": False}


@app.post("/parse-document")
async def parse_document(file: UploadFile = File(...)):
    try:
        file_bytes = await file.read()
        if len(file_bytes) > 50 * 1024 * 1024:
            raise HTTPException(status_code=413, detail="File too large")

        filename = file.filename or ""
        lower_name = filename.lower()
        content_type = (file.content_type or "").lower()

        pages = 1
        text = ""

        if lower_name.endswith(".pdf") or "pdf" in content_type:
            doc = fitz.open(stream=file_bytes, filetype="pdf")
            pages = len(doc)
            text = "".join(page.get_text() for page in doc)
            doc.close()
        elif lower_name.endswith(".docx") or "word" in content_type or "officedocument" in content_type:
            doc = DocxDocument(io.BytesIO(file_bytes))
            pages = 1
            text = "\n".join(p.text for p in doc.paragraphs)
        elif lower_name.endswith(".txt") or content_type.startswith("text/plain"):
            pages = 1
            text = file_bytes.decode("utf-8", errors="ignore")
        else:
            raise HTTPException(status_code=415, detail="Unsupported file type")

        word_count = len(text.split())
        logger.info(
            f"Parsed {filename}, {pages} pages, {word_count} words",
            extra={"endpoint": "/parse-document", "user_uuid": ""},
        )

        return {
            "text": text,
            "pages": pages,
            "word_count": word_count,
            "file_name": filename,
        }
    except HTTPException as http_exc:
        logger.error(
            f"Error in /parse-document: {str(http_exc.detail)}",
            extra={"endpoint": "/parse-document", "user_uuid": ""},
        )
        return error_response(
            http_exc.status_code,
            str(http_exc.detail),
            "UNSUPPORTED_FILE" if http_exc.status_code == 415 else "PARSE_FAILED",
        )
    except Exception as e:
        logger.error(
            f"Error in /parse-document: {str(e)}",
            extra={"endpoint": "/parse-document", "user_uuid": ""},
        )
        return error_response(500, "Document parsing failed", "PARSE_FAILED")


@app.post("/getResponse")
async def get_response(request: PromptRequest):
    conn = None
    cursor = None

    try:
        if request.first_request:
            session_uuid = str(uuid.uuid4())
            previous_history = []
        else:
            if not request.uuid:
                return error_response(400, "uuid is required for follow-up requests", "BAD_INPUT")
            session_uuid = request.uuid
            try:
                previous_history = redis_client.lrange(session_uuid, 0, -1)
                if not previous_history:
                    previous_history = []
            except Exception as redis_error:
                logger.error(
                    f"Error in /getResponse (redis): {str(redis_error)}",
                    extra={"endpoint": "/getResponse", "user_uuid": session_uuid},
                )
                previous_history = []

        conn, cursor = get_db_connection()
        schema_info = get_schema_info(cursor, ["CTM", "inventorymulti2"])
        if not schema_info:
            return error_response(503, "Dependency unavailable", "SCHEMA_UNAVAILABLE")

        try:
            sql_query = run_mysql_query_agent(
                schema_info=schema_info,
                details=cached_details,
                user_request=request.prompt,
                model="qwen3:8b",
            )
        except Exception as ai_error:
            logger.error(
                f"Error in /getResponse (crewai): {str(ai_error)}",
                extra={"endpoint": "/getResponse", "user_uuid": session_uuid},
            )
            return error_response(503, "AI service temporarily unavailable", "AI_UNAVAILABLE")

        cleaned_sql = clean_llm_response(sql_query)

        if not cleaned_sql.upper().startswith("SELECT"):
            return error_response(500, "AI service temporarily unavailable", "AI_INVALID_SQL")

        try:
            result = execute_query(cursor, cleaned_sql)
            if result is None:
                return error_response(500, "Database query failed", "DB_QUERY_FAILED")
        except Exception as db_error:
            logger.error(
                f"Error in /getResponse (mysql): {str(db_error)}",
                extra={"endpoint": "/getResponse", "user_uuid": session_uuid},
            )
            return error_response(500, "Database query failed", "DB_QUERY_FAILED")

        try:
            redis_client.rpush(session_uuid, "response", cleaned_sql, "request", request.prompt)
        except Exception as redis_error:
            logger.error(
                f"Error in /getResponse (redis push): {str(redis_error)}",
                extra={"endpoint": "/getResponse", "user_uuid": session_uuid},
            )

        return {"response": result, "uuid": session_uuid}
    except HTTPException as http_exc:
        logger.error(
            f"Error in /getResponse: {str(http_exc.detail)}",
            extra={"endpoint": "/getResponse", "user_uuid": request.uuid or ""},
        )
        return error_response(http_exc.status_code, str(http_exc.detail), "DB_CONNECTION_FAILED")
    except Exception as e:
        logger.error(
            f"Error in /getResponse: {str(e)}",
            extra={"endpoint": "/getResponse", "user_uuid": request.uuid or ""},
        )
        return error_response(500, "Internal server error", "INTERNAL_ERROR")
    finally:
        close_connection(conn, cursor)


@app.get("/removeSession/{session_uuid}")
async def remove_session(session_uuid: str):
    try:
        exists = redis_client.exists(session_uuid)
        if exists == 0:
            return error_response(404, "Session UUID does not exist", "SESSION_NOT_FOUND")
        redis_client.delete(session_uuid)
        return {"message": f"Session {session_uuid} removed successfully."}
    except Exception as e:
        logger.error(
            f"Error in /removeSession: {str(e)}",
            extra={"endpoint": "/removeSession", "user_uuid": session_uuid},
        )
        return error_response(500, "Internal server error", "INTERNAL_ERROR")


@app.post("/cache/reload")
async def reload_cache(request: Request, x_admin_token: Optional[str] = Header(default=None)):
    try:
        if not _is_admin_request(request, x_admin_token):
            return error_response(403, "Forbidden", "FORBIDDEN")
        total = _reload_cached_details()
        logger.info(
            f"Cache reloaded with {total} records",
            extra={"endpoint": "/cache/reload", "user_uuid": ""},
        )
        return {"message": "Cache reloaded", "cached_details_count": total}
    except Exception as e:
        logger.error(
            f"Error in /cache/reload: {str(e)}",
            extra={"endpoint": "/cache/reload", "user_uuid": ""},
        )
        return error_response(500, "Cache reload failed", "CACHE_RELOAD_FAILED")


# 11. Main block
if __name__ == "__main__":
    uvicorn.run(app, host=APP_HOST, port=APP_PORT)
