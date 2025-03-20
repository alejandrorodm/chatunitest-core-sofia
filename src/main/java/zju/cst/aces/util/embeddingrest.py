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

        embeddings = generate_embedding(code)
        if embeddings is None:
            return jsonify({'error': 'Error generating embeddings'}), 500

        # Guardar en la base de datos vectorial
        try:
            collection.add(
                ids=[class_name],
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
        signature= data.get('signature')
        results = None
        query_embedding = None
        code = None
        
        
        if class_name and signature:
            print(f"Se ha encontrado el nombre de la clase {class_name} y del método {signature}")
            try:
                
                results = collection.get(
                    ids=[class_name],
                    include=["metadatas", "embeddings"]
                )

                # Ahora filtramos los resultados manualmente por el método y la firma
                if results["metadatas"]:
                    for meta in results["metadatas"]:
                        print(meta.get("class_name"), meta.get("signature"))
                        if (
                            meta.get("class_name") == class_name
                            and meta.get("signature") == signature
                        ):
                            print("✅ Código encontrado:", meta)
                            query_embedding = meta.get("embeddings")

                            if query_embedding is None:
                                query_embedding = generate_embedding(meta.get("code"))
                            
                            break
                    else:
                        print("❌ No se encontró el código con ese método y firma")
                else:
                    print("❌ No se encontró la clase")
                
            except Exception as e:
                print("Error en la búsqueda por nombre de clase y método: ", e)
            else:
                print("Embedding del código encontrado:", query_embedding)                
        else:
            print("No se ha encontrado el nombre de la clase y del método")
            
        if query_embedding is None:
            print("El embedding del resultado encontrado es nulo. Se generará un nuevo embedding con el código dado")
            query_embedding = generate_embedding(data.get('code'))
            
        try:
            results = collection.query(
                query_embeddings=[query_embedding],
                n_results=5
            )
        except Exception as e:
            print("No se ha podido encontrar por código: ", e)
            return jsonify({'error': 'No matching code found'}), 404
        else:
            if not results or not results["ids"]:
                return jsonify({'error': 'No matching code found'}), 404
            else:
                print("Se han encontrado resultados")
    
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
