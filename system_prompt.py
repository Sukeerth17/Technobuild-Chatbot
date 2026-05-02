import json
import os
import re
from crewai import Agent, Task, Crew
from langchain_community.llms import Ollama

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")


def _build_agent(role: str, goal: str, backstory: str, model: str) -> Agent:
    llm = Ollama(model=model, base_url=OLLAMA_BASE_URL, temperature=0.1)
    return Agent(
        role=role,
        goal=goal,
        backstory=backstory,
        llm=llm,
        memory=False,
        verbose=True,
    )

def clean_codeblock(text):
    if not text:
        return ""
    text = text.replace("```", "").strip()
    return text

def extract_json(text):
    text = clean_codeblock(text)
    try:
        return json.loads(text)
    except ValueError:
        m = re.search(r"\{.*\}", text, re.DOTALL)
        if m:
            try:
                return json.loads(m.group(0))
            except:
                return None
    return None


def run_mysql_query_agent(schema_info, details, user_request, model="qwen3:8b"):
    """
    Full CrewAI version replicating your original logic.
    Runs:
      Task 1 -> Schema selection
      Task 2 -> SQL generation
    """
    schema_selector = _build_agent(
        role="Schema & Table Selector",
        goal="Return ONLY strict JSON",
        backstory="You are a strict schema selector. Given schema + details + user request, return ONLY one JSON object with keys: database, tables, columns. No explanations, no markdown, no extra text.",
        model=model,
    )

    sql_generator = _build_agent(
        role="SQL Query Generator",
        goal="Return ONE valid MySQL SELECT query",
        backstory="You are a strict SQL generator. Given validated schema selection and user intent, return exactly one syntactically correct MySQL SELECT query. No explanations, no markdown.",
        model=model,
    )

    selector_task = Task(
        agent=schema_selector,
        description=f"""
You MUST return ONLY one JSON object with keys:
- "database": string
- "tables": [string]
- "columns": [string]

RULES:
- NO explanation.
- NO markdown.
- NO extra text.
- If impossible return EXACTLY: {{"error":"INVALID"}}

SCHEMA:
{json.dumps(schema_info)}

DETAILS:
{json.dumps(details)}

USER REQUEST:
{user_request}

VALID OUTPUT EXAMPLE:
{{"database":["databasename"],"tables":["project_details"],"columns":["id","address"]}}
""",
        expected_output="Strict JSON only."
    )

    sql_task = Task(
        agent=sql_generator,
        description=f"""
Use ONLY the JSON output from Task 1.

RULES:
- ONE MySQL SELECT.
- Fully qualified tables: `database.table`
- No invented columns.
- No markdown.
- No explanations.
- MUST start with SELECT.
- If impossible return EXACTLY: SELECT NULL WHERE FALSE;

USER REQUEST:
{user_request}
""",
        expected_output="SQL only."
    )

    crew = Crew(
        agents=[schema_selector, sql_generator],
        tasks=[selector_task, sql_task],
        verbose=True
    )

    result = crew.kickoff()

    if isinstance(result, dict) and "final_output" in result:
        final_text = result["final_output"]
    else:
        final_text = str(result)

    final_text = clean_codeblock(final_text)

    if not final_text.strip().upper().startswith("SELECT"):
        return "SELECT NULL WHERE FALSE;"

    return final_text.strip()
