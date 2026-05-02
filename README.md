# TechnoBuild Chatbot (Text-to-SQL API)

This project is a FastAPI-based Text-to-SQL chatbot that uses local LLMs through Ollama to convert natural language prompts into MySQL `SELECT` queries.

## Repository

```bash
git clone https://github.com/Sukeerth17/Technobuild-Chatbot.git
cd Technobuild-Chatbot
```

## Is LLM Download Required?

Yes. The API depends on Ollama models running locally.

Current defaults used by this codebase:

- Main generation model: `qwen3:8b`
- Embedding model: `nomic-embed-text`

## Requirements

- Python 3.11 (recommended for speed and compatibility)
- MySQL server access
- Redis server
- Ollama
- Dependencies in `requirements.txt`
- CrewAI (installed via `requirements.txt`)

## Install Ollama

### macOS

Option 1 (Homebrew):

```bash
brew install ollama
```

Option 2:

- Download and install from: https://ollama.com/download

### Windows

Option 1 (winget):

```powershell
winget install Ollama.Ollama
```

Option 2:

- Download and install from: https://ollama.com/download/windows

## Download Required Models

After installing Ollama, start it and pull models:

```bash
ollama serve
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

Verify models:

```bash
ollama list
```

## Project Setup

1. Create and activate a Python 3.11 environment.

Recommended (Conda):

```bash
conda create -n technochat python=3.11 -y
conda activate technochat
```

Alternative (venv, if `python3.11` is installed):

macOS/Linux:

```bash
python3.11 -m venv env
source env/bin/activate
```

Windows (PowerShell):

```powershell
python -m venv env
.\env\Scripts\Activate.ps1
```

2. Install dependencies:

```bash
pip install -r requirements.txt
```

3. Configure environment variables in `.env` (or copy from `.env.example`):

```env
DB_HOST=
DB_USER=
DB_PASSWORD=
DB_PORT=3306
REDIS_HOST=localhost
REDIS_PORT=6379
OLLAMA_BASE_URL=http://localhost:11434
EMBED_MODEL=nomic-embed-text
```

4. Ensure Redis is running (`localhost:6379` by default).

## Run the API

```bash
uvicorn app:app --reload
```

Default URL: `http://127.0.0.1:8000`

## Health Checks

```bash
curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/ready
```

`/ready` checks Ollama, Redis, MySQL, and verifies that `qwen3:8b` is available.

## Project Structure

- `app.py` - FastAPI app and endpoints
- `db.py` - MySQL connection and schema extraction
- `llm_result.py` - Ollama request helpers
- `system_prompt.py` - CrewAI agents and SQL generation workflow
- `requirements.txt` - Python dependencies
