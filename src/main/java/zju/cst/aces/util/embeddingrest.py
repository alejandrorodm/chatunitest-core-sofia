from flask import Flask, request, jsonify
from transformers import AutoTokenizer, AutoModel
import torch
import chromadb

app = Flask(__name__)

# Chroma with persistency
chroma_client = chromadb.Client(
    chromadb.config.Settings(
        persist_directory="./chroma_data"  # Carpeta donde guardamos los datos
    )
)

collection = chroma_client.get_or_create_collection(name="codebase")

# Cargar el modelo
tokenizer = AutoTokenizer.from_pretrained("bert-base-uncased")
model = AutoModel.from_pretrained("bert-base-uncased")


# Función para generar embeddings
def generate_embedding(text):
    inputs = tokenizer(text, return_tensors="pt", truncation=True, padding=True)
    outputs = model(**inputs)
    embeddings = outputs.last_hidden_state.mean(dim=1).detach().numpy().tolist()[0]
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
        code = data.get('code')

        if not class_name or not code:
            return jsonify({'error': 'Missing class name or code'}), 400

        embedding = generate_embedding(code)

        # Guardar en la base de datos vectorial
        try:
            collection.add(
                ids=[class_name],
                embeddings=[embedding],
                metadatas=[{"code": code}]
            )
        except Exception as e:
            return jsonify({'Error en el guardado en la base de datos': str(e)}), 500
        else:
            return jsonify({'message': 'Code saved successfully'})
    except Exception as e:
        print(f"Error interno: {e}")
        return jsonify({'error': str(e)}), 500
    

# Buscar código por similitud
@app.route('/search_code', methods=['POST'])
def search_code():
    try:
        data = request.json
        query = data.get('query')

        if not query:
            return jsonify({'error': 'Missing query'}), 400

        query_embedding = generate_embedding(query)

        # Códigos similares
        results = collection.query(
            query_embeddings=[query_embedding],
            n_results=5
        )

        # Extraer nombres de clase y códigos encontrados
        matched_classes = results["ids"][0] if results["ids"] else []
        matched_codes = [meta["code"] for meta in results["metadatas"][0]] if results["metadatas"] else []

        return jsonify({'results': matched_classes, 'codes': matched_codes})

    except Exception as e:
        return jsonify({'error': str(e)}), 500


if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
