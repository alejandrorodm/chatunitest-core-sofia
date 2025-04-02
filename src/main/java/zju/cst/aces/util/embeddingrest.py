from flask import Flask, request, jsonify
from transformers import AutoTokenizer, AutoModel
import torch
import chromadb
from waitress import serve
import numpy as np

app = Flask(__name__)

# Chroma with persistency
chroma_client = chromadb.PersistentClient()
collection = chroma_client.get_or_create_collection(name="codebase")

# Cargar el modelo
tokenizer = AutoTokenizer.from_pretrained("bert-base-uncased")
model = AutoModel.from_pretrained("bert-base-uncased")

def generate_embedding(text):
    inputs = tokenizer(text, return_tensors="pt", truncation=True, padding=True)
    outputs = model(**inputs)
    embeddings = outputs.last_hidden_state.mean(dim=1).detach().numpy().tolist()[0]
    return embeddings

def cosine_similarity(vec1, vec2):
    return np.dot(vec1, vec2) / (np.linalg.norm(vec1) * np.linalg.norm(vec2))

@app.route('/save_code', methods=['POST'])
def save_code():
    try:
        data = request.json
        class_name = data.get('class_name')
        code = data.get('code')
        comment = data.get('comment')
        annotations = data.get('annotations')
        if not class_name or not code:
            return jsonify({'error': 'Missing class name or code'}), 400
        embeddings = generate_embedding(code)
        collection.add(
            ids=[class_name],
            embeddings=[embeddings],
            metadatas=[{
                "class_name": class_name,
                "code": code,
                "comment": comment,
                "annotations": annotations
            }]
        )
        return jsonify({'message': 'Code saved successfully'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/save_methods', methods=['POST'])
def save_methods():
    try:
        data = request.json
        class_name = data.get('class_name')
        methods = data.get('methods', [])

        if not class_name or not methods:
            return jsonify({'error': 'Missing class name or methods'}), 400

        for method in methods:
            method_name = method.get('method_name')
            signature = method.get('signature')
            code = method.get('code')
            comment = method.get('comment')
            annotations = method.get('annotations')

            if not method_name or not code:
                continue

            embeddings = generate_embedding(code)
            method_id = f"{class_name}:{method_name}"

            collection.add(
                ids=[method_id],
                embeddings=[embeddings],
                metadatas=[{
                    "class_name": class_name,
                    "method_name": method_name,
                    "signature": signature,
                    "code": code,
                    "comment": comment,
                    "annotations": annotations
                }]
            )

        return jsonify({'message': 'Methods saved successfully'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/search_similar_methods', methods=['POST'])
def search_similar_methods():
    try:
        data = request.json
        class_name = data.get('class_name', '')
        method_name = data.get('method_name', '')

        code = data.get('code')
        max_neighbours = data.get('max_neighbours', 5)
        similarity_threshold = 0.85

        if not code:
            return jsonify({'error': 'Missing code'}), 400

        query_embedding = generate_embedding(code)

        # Búsqueda en la base de datos
        try:
            results = collection.query(
                query_embeddings=[query_embedding],
                n_results=max_neighbours * 2,  # Extra para filtrar mejor luego
                include=["metadatas", "embeddings"]
            )
        except Exception as e:
            return jsonify({'error': 'Error retrieving similar methods', 'details': str(e)}), 500

        matched_results = []
        for meta, neighbor_embedding in zip(results["metadatas"][0], results["embeddings"][0]):
            similarity = cosine_similarity(query_embedding, neighbor_embedding)

            if similarity < similarity_threshold or similarity >= 1:
                continue  # Evita valores irrelevantes

            if meta.get("class_name") == class_name and meta.get("method_name") == method_name:
                continue  # Evita devolver el mismo método que se está buscando

            matched_results.append({
                "class_name": meta.get("class_name"),
                "method_name": meta.get("method_name"),
                "signature": meta.get("signature"),
                "code": meta.get("code"),
                "comment": meta.get("comment"),
                "annotations": meta.get("annotations"),
                "similarity": round(similarity, 2)
            })

            if len(matched_results) >= max_neighbours:
                break

        if not matched_results:
            return jsonify({'error': 'No similar methods found'}), 404

        return jsonify({'results': matched_results})

    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    serve(app=app, host='127.0.0.1', port=5000)
