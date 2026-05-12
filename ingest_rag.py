import os
import requests
import psycopg2
import uuid
import hashlib

DB_PARAMS = {
    "host": "localhost",
    "port": "5433",
    "dbname": "chatbot_db",
    "user": "chatbot_user",
    "password": "StrongPassword123!"
}

AI_API_BASE = "http://localhost:8000"
DOCS_DIR = os.path.join(os.path.dirname(__file__), "..", "rag_documents")

def main():
    print("Starting standalone RAG ingestion...")
    try:
        conn = psycopg2.connect(**DB_PARAMS)
        cursor = conn.cursor()
    except Exception as e:
        print(f"Failed to connect to PostgreSQL: {e}")
        return

    if not os.path.exists(DOCS_DIR):
        print(f"Error: {DOCS_DIR} does not exist.")
        return

    files = [f for f in os.listdir(DOCS_DIR) if f.endswith(".md")]
    if not files:
        print(f"No Markdown files found in {DOCS_DIR}.")
        return

    for filename in files:
        filepath = os.path.join(DOCS_DIR, filename)
        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()

        file_hash = hashlib.sha256(content.encode('utf-8')).hexdigest()
        job_id = str(uuid.uuid4())

        print(f"\n======================================")
        print(f"Processing file: {filename}")
        print(f"======================================")
        
        # We explicitly configure the chunk sizes based on the optimal 
        # Markdown heading boundaries we analyzed.
        chunk_payload = {
            "text": content,
            "chunk_size": 150,
            "chunk_overlap": 25
        }
        
        print(f"Calling {AI_API_BASE}/chunk with size 150, overlap 25...")
        try:
            chunk_res = requests.post(f"{AI_API_BASE}/chunk", json=chunk_payload)
            chunk_res.raise_for_status()
        except requests.exceptions.RequestException as e:
            print(f"Failed to reach /chunk endpoint. Is the Python AI service running? Error: {e}")
            continue
            
        chunks = chunk_res.json().get("chunks", [])
        print(f"Generated {len(chunks)} tight semantic chunks.")

        try:
            # 1. Insert into documents table
            cursor.execute(
                """
                INSERT INTO documents 
                (job_id, file_name, file_hash, file_type, category, audience, status, chunk_count, uploaded_by, created_at) 
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, NOW()) RETURNING id;
                """,
                (job_id, filename, file_hash, 'text/markdown', 'KNOWLEDGE_BASE', 'INTERNAL', 'COMPLETED', len(chunks), 1)
            )
            document_id = cursor.fetchone()[0]
            print(f"Successfully tracked document '{filename}' in DB with ID: {document_id}")

            # 2. Iterate and Embed
            for i, chunk_text in enumerate(chunks):
                embed_payload = {"text": chunk_text}
                embed_res = requests.post(f"{AI_API_BASE}/embed", json=embed_payload)
                embed_res.raise_for_status()
                embedding = embed_res.json().get("embedding")
                
                # Format vector array for Postgres pgvector type mapping
                vector_str = "[" + ",".join(str(v) for v in embedding) + "]"
                
                # 3. Insert into vector_chunks table
                cursor.execute(
                    """
                    INSERT INTO vector_chunks 
                    (document_id, content, embedding, chunk_index, token_count, category, audience, created_at)
                    VALUES (%s, %s, %s::vector, %s, %s, %s, %s, NOW());
                    """,
                    (document_id, chunk_text, vector_str, i, len(chunk_text.split()), "KNOWLEDGE_BASE", "INTERNAL")
                )
                
            print(f"SUCCESS: Embedded and inserted all {len(chunks)} chunks for {filename}.")
            conn.commit()
            
        except Exception as e:
            conn.rollback()
            print(f"Database error processing {filename}: {e}")

    cursor.close()
    conn.close()
    print("\nStandalone ingestion script complete! Data is ready for RAG.")

if __name__ == "__main__":
    main()
