from flask import Flask, request, jsonify
from transformers import AutoTokenizer, AutoModel
import torch
import chromadb
from waitress import serve
import os
import numpy as np

# Crear el directorio si no existe
if not os.path.exists("./chroma_data"):
    os.makedirs("./chroma_data")

app = Flask(__name__)

# Chroma with persistency
chroma_client = chromadb.PersistentClient()


collection = chroma_client.get_or_create_collection(name="codebase")

# Cargar el modelo
tokenizer = AutoTokenizer.from_pretrained("bert-base-uncased")
model = AutoModel.from_pretrained("bert-base-uncased")


# Función para generar embeddings
def generate_embedding(text):
    inputs = tokenizer(text, return_tensors="pt", truncation=True, padding=True)
    outputs = model(**inputs)
    embeddings = outputs.last_hidden_state.mean(dim=1).detach().numpy().tolist()[0]  # Convierte ndarray a lista
    print("Embeddings generados")
    return embeddings


@app.route('/')
def index():
    return 'Hello, World!'

# Ruta para guardar código
@app.route('/save_code', methods=['POST'])
def save_code():
    try:
        data = request.json
        class_name = data.get('class_name')
        method_name = data.get('method_name')
        code = data.get('code')
        signature = data.get('signature')
        comment = data.get('comment')
        annotations = data.get('annotations')

        if not class_name or not code:
            return jsonify({'error': 'Missing class name or code'}), 400

        embeddings = generate_embedding(code)

        if embeddings is None:
            return jsonify({'error': 'Error generating embeddings'}), 500

        id = class_name + signature
        
        # Guardar en la base de datos vectorial
        try:
            collection.add(
                ids=[id],
                embeddings=[embeddings],
                metadatas=[{
                    "class_name": class_name,
                    "code": code,
                    "method_name": method_name,
                    "signature": signature,
                    "comment": comment,
                    "annotations": annotations
                }]
            )
        except Exception as e:
            print(f"Error en el guardado en la base de datos: {e}")
            return jsonify({'Error en el guardado en la base de datos': str(e)}), 500
        else:
            print(f"Código {method_name} con {signature} guardado correctamente")
            return jsonify({'message': 'Code saved successfully'})
    except Exception as e:
        print(f"Error interno: {e}")
        return jsonify({'error': str(e)}), 500
    

# Buscar código por similitud
@app.route('/search_code', methods=['POST'])
def search_code():
    try:
        data = request.json
        class_name = data.get('class_name')
        signature = data.get('signature')
        max_neighbours = data.get('max_neighbours', 8)
        similarity_threshold = 0.7

        results = None
        query_embedding = None

        # Initial search by class name and method signature
        if class_name and signature:
            try:
                results = collection.get(
                    where={
                        "$and": [
                            {"class_name": {"$eq": class_name}},
                            {"signature": {"$eq": signature}}
                        ]
                    },
                    include=["metadatas", "embeddings"]
                )
                query_embedding = results["embeddings"]
                query_embedding = query_embedding.tolist()[0]
            except Exception as e:
                print("Error en la búsqueda por nombre de clase y método: ", e)
                query_embedding = None

        # If not found, search by embedding the code
        if query_embedding is None:
            query_embedding = generate_embedding(data.get('code'))

        # Search by near embeddings
        try:
            results = collection.query(
                query_embeddings=[query_embedding],
                n_results=max_neighbours,
                include=["metadatas", "embeddings"]
            )
        except Exception as e:
            print("No se ha podido encontrar por código: ", e)
            return jsonify({'error': 'No matching code found'}), 404

        # Empaquetamos los resultados en objetos completos
        matched_results = []
        if results and results["metadatas"]:
            neighbors = zip(results["metadatas"][0], results["embeddings"][0])
            
            # Calculamos la similitud del coseno
            import numpy as np
            def cosine_similarity(vec1, vec2):
                return np.dot(vec1, vec2) / (np.linalg.norm(vec1) * np.linalg.norm(vec2))

            for meta, neighbor_embedding in neighbors:
                similarity = cosine_similarity(query_embedding, neighbor_embedding)
                print(f"Similitud: {similarity:.2f}")
                
                if similarity < similarity_threshold or similarity >= 1:
                    continue

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
            return jsonify({'error': 'No matching code found'}), 404

        return jsonify({'results': matched_results})

    except Exception as e:
        return jsonify({'error': str(e)}), 500




if __name__ == '__main__':
    #app.run(debug=True, host='0.0.0.0', port=5000)
    serve(app=app, host='127.0.0.1', port=5000)