import json
import os
import re
from crewai import Agent, Task, Crew
from langchain_community.llms import Ollama

OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")

SEMANTIC_CONTEXT = """
TechnoBuild CRM Business Database Semantic Schema:

1. LEADS table:
   - This is the core table representing potential clients.
   - Column `IS_CLIENT`: Boolean flag. If IS_CLIENT=1, this lead has converted into a paying customer. Do not confuse leads and clients.
   - Column `BUDGET`: Estimated budget for the project. Higher is better.
   - Column `TIMELINE`: Expected decision timeline.
   - Column `SITE_VISIT_REQUIRED`: Boolean flag for mandatory physical visits.

2. ACTIVITIES table:
   - This table is PAST-FACING. It represents interactions (calls, meetings, notes) that have already happened.
   - Column `OUTCOME`: Values like 'Positive', 'Negative', 'Neutral'.
   - NEVER query this table for future scheduled tasks.

3. FOLLOW_UP table:
   - This table is FUTURE-FACING. It represents scheduled tasks (calls, site visits, proposals) that have not happened yet.
   - Whenever the user asks for "pending tasks", "scheduled items", or "what do I have to do next", query this table.

4. STAGE table:
   - Represents the sales pipeline stage (e.g., Prospect, Proposal, Negotiation).
   - Column `PROBABILITY`: A percentage (0-100) representing the likelihood of closing the deal at this stage. 

5. SOURCE table:
   - Represents marketing channels (e.g., Google Ads, Referral, Walk-in).
   - Column `COST_PER_LEAD`: Financial cost to acquire a lead. Lower value means better ROI.

6. USER_DETAILS table:
   - Represents the internal staff (Sales Reps, Managers, Admins) using the system.
   - If a user asks "Show me MY leads" or "my follow-ups", filter the corresponding table using the assigned user ID.

7. Communication Tables (CHAT_MESSAGE, CHAT_GROUP, CHAT_GROUP_MESSAGE):
   - Internal messaging system similar to Slack built into the app.
"""

SQL_EXAMPLES = """
Example Natural Language to SQL Mappings:

1. Request: "Show me all active paying clients"
   SQL: SELECT * FROM LEADS WHERE IS_CLIENT = 1;

2. Request: "What is my pending task list for today?"
   SQL: SELECT * FROM FOLLOW_UP WHERE FOLLOW_UP_DATE = CURDATE();

3. Request: "Show me the outcome of all past activities"
   SQL: SELECT OUTCOME, COUNT(*) FROM ACTIVITIES GROUP BY OUTCOME;

4. Request: "Which leads have the highest probability of closing?"
   SQL: SELECT L.LEAD_NAME, S.STAGE_NAME, S.PROBABILITY FROM LEADS L JOIN STAGE S ON L.STAGE_ID = S.STAGE_ID ORDER BY S.PROBABILITY DESC LIMIT 10;

5. Request: "What marketing source gives us the best ROI?"
   SQL: SELECT SOURCE_NAME, COST_PER_LEAD FROM SOURCE ORDER BY COST_PER_LEAD ASC LIMIT 1;

6. Request: "How many hot leads do we have?"
   SQL: SELECT COUNT(*) FROM LEADS L JOIN STAGE S ON L.STAGE_ID = S.STAGE_ID WHERE S.PROBABILITY > 75 AND L.IS_CLIENT = 0;

7. Request: "Show me the successful past meetings"
   SQL: SELECT * FROM ACTIVITIES WHERE ACTIVITY_TYPE = 'Meeting' AND OUTCOME = 'Positive';

8. Request: "What is the total budget of our current pipeline?"
   SQL: SELECT SUM(BUDGET) FROM LEADS WHERE IS_CLIENT = 0;

9. Request: "Who are the top 5 sales reps by converted clients?"
   SQL: SELECT U.FULL_NAME, COUNT(L.LEADS_ID) AS CONVERSIONS FROM USER_DETAILS U JOIN LEADS L ON U.USER_ID = L.ASSIGNED_USER_ID WHERE L.IS_CLIENT = 1 GROUP BY U.USER_ID ORDER BY CONVERSIONS DESC LIMIT 5;

10. Request: "Are there any leads needing a site visit?"
    SQL: SELECT LEAD_NAME, LEAD_EMAIL FROM LEADS WHERE SITE_VISIT_REQUIRED = 1 AND IS_CLIENT = 0;
"""

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
    text = text.replace("```sql", "").replace("```", "").strip()
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

def run_mysql_query_agent(schema_info, user_request, model="qwen3:8b"):
    schema_selector = _build_agent(
        role="Schema & Table Selector",
        goal="Return ONLY strict JSON",
        backstory="You are a strict schema selector. Given the semantic schema and user request, return ONLY one JSON object with keys: database, tables, columns. No explanations.",
        model=model,
    )

    sql_generator = _build_agent(
        role="SQL Query Generator",
        goal="Return ONE valid MySQL SELECT query",
        backstory="You are an expert SQL generator for the TechnoBuild CRM. You must understand business logic (e.g. IS_CLIENT=1 means a paying customer, ACTIVITIES is past, FOLLOW_UP is future). Return exactly one syntactically correct MySQL SELECT query. No explanations.",
        model=model,
    )

    selector_task = Task(
        agent=schema_selector,
        description=f'''
You MUST return ONLY one JSON object with keys:
- "database": string
- "tables": [string]
- "columns": [string]

RULES:
- NO explanation.
- NO markdown.
- NO extra text.
- If impossible return EXACTLY: {{"error":"INVALID"}}

PHYSICAL DB SCHEMA:
{json.dumps(schema_info)}

SEMANTIC BUSINESS CONTEXT:
{SEMANTIC_CONTEXT}

USER REQUEST:
{user_request}

VALID OUTPUT EXAMPLE:
{{"database":["business_db"],"tables":["LEADS"],"columns":["LEADS_ID","BUDGET"]}}
''',
        expected_output="Strict JSON only."
    )

    sql_task = Task(
        agent=sql_generator,
        description=f'''
Use ONLY the JSON output from Task 1 and the following context.

SEMANTIC BUSINESS CONTEXT:
{SEMANTIC_CONTEXT}

{SQL_EXAMPLES}

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
''',
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

