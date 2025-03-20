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
        method_name = data.get('method_name')
        code = data.get('code')
        signature = data.get('signature')
        comment = data.get('comment')
        annotations = data.get('annotations')

        if not class_name or not code:
            return jsonify({'error': 'Missing class name or code'}), 400

        embedding = generate_embedding(code)

        # Guardar en la base de datos vectorial
        try:
            collection.add(
                ids=[class_name],
                embeddings=[embedding],
                metadatas=[{"code": code, "method_name": method_name, "signature": signature, "comment": comment, "annotations": annotations}]
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
        method_name = data.get('method_name')
        results = None
        
        if class_name and method_name:
            print("Se ha encontrado el nombre de la clase y del método")
            try:
                results = collection.get(
                    where={"class_name": class_name, "method_name": method_name}
                )
            except Exception as e:
                print("No se han encontrado resultados buscando por el nombre de la clase y metodo")

                try:
                    code = data.get('code')
                except:
                    print("Se ha intentado buscar por embeddings pero no se ha encontrado el código de petición")
                    return jsonify({'error': 'No matching code found'}), 404
                else:
                    query_embedding = generate_embedding(code)
                    results = collection.query(
                        query_embeddings=[query_embedding],
                        n_results=5
                    )
            else: 
                print("Se ha encontrado el código por nombre de clase y método")
                print(results)
            
    
        # Extraer nombres de clase y códigos encontrados
        matched_classes = results["ids"][0] if results["ids"] else []
        matched_codes = [meta["code"] for meta in results["metadatas"][0]] if results["metadatas"] else []
        matched_methods = [meta["method_name"] for meta in results["metadatas"][0]] if results["metadatas"] else []
        matched_signatures = [meta["signature"] for meta in results["metadatas"][0]] if results["metadatas"] else []
        matched_comments = [meta["comment"] for meta in results["metadatas"][0]] if results["metadatas"] else []
        matched_annotations = [meta["annotations"] for meta in results["metadatas"][0]] if results["metadatas"] else []
        
        return jsonify({'results': matched_classes, 'codes': matched_codes, 'methods': matched_methods, 'signatures': matched_signatures, 'comments': matched_comments, 'annotations': matched_annotations})

    except Exception as e:
        return jsonify({'error': str(e)}), 500


if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
