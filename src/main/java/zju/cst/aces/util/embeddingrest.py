from flask import Flask, request, jsonify
from transformers import AutoTokenizer, AutoModel
import torch
import chromadb
from waitress import serve
import numpy as np
import json
import re

app = Flask(__name__)

# Chroma with persistency
chroma_client = chromadb.PersistentClient()
collection = chroma_client.get_or_create_collection(name="codebase")

# Cargar el modelo
tokenizer = AutoTokenizer.from_pretrained("microsoft/codebert-base")
model = AutoModel.from_pretrained("microsoft/codebert-base")

def generate_embedding(text):
    inputs = tokenizer(text, return_tensors="pt", truncation=True, padding=True)
    outputs = model(**inputs)
    embeddings = outputs.last_hidden_state.mean(dim=1).detach().numpy().tolist()[0]
    return embeddings


def check_if_id_exists(unique_id):
    try:
        # Buscar si el ID ya existe
        existing = collection.get(ids=[unique_id])
        ids = existing.get('ids', [])
            
        # Comprobar si existe en la respuesta
        if existing and ids:
            print(existing)
            print(f"Existing IDs: {ids}")
            return True # El ID ya existe, no se debe insertar
        else:
            return False  # El ID no existe, se puede insertar

    except Exception as e:
        print(f"Error al comprobar existencia de ID {unique_id}: {e}")
        return False # Si ocurre un error, lo manejamos como si no existiera

        
def cosine_similarity(vec1, vec2):
    return np.dot(vec1, vec2) / (np.linalg.norm(vec1) * np.linalg.norm(vec2))

@app.route('/', methods=['GET'])
def home():
    return "Hello!", 200

@app.route('/save_code', methods=['POST'])
def save_code():
    try:
        data = request.json
        class_name = data.get('class_name')
        method_name = data.get('method_name')
        signature = data.get('signature')
        code = data.get('code')
        
        comment = data.get('comment', '')
        annotations = data.get('annotations', '')
        dependent_methods = data.get('dependent_methods', [])
        dependent_classes = data.get('dependent_classes', "")
        
        if not class_name or not  method_name or not code:
            return jsonify({'error': 'Missing class name or code'}), 400
        
        # To avoid errores on expected metadata value
        dependent_methods_str = json.dumps(dependent_methods)
        
        # Verificar si ya existe
        unique_id = class_name + '-' + signature  # Define unique_id
        
        is_constructor = method_name == class_name  # Constructor check
        
        if not check_if_id_exists(unique_id):
            print(f'Code to be saved: Class {class_name}, Signature: {signature}, Dep_classes: {dependent_classes}')
            embeddings = generate_embedding(code)
            collection.add(
                ids=[unique_id],
                embeddings=[embeddings],
                metadatas=[{
                    "class_name": class_name,
                    "method_name": method_name,
                    "signature": signature,
                    "code": code,
                    #"comment": comment,
                    #"annotations": annotations,
                    #"dependent_methods": dependent_methods_str,
                    "dependent_classes": str(dependent_classes),
                    "is_constructor": str(is_constructor)  # Constructor check
                }]
            )
            return jsonify({'message': 'Code saved successfully'})
        else:
            print(f"ID {unique_id} ya existe. Se omite.")
            
            # Actualizar el registro existente añadiendo el nuevo dependent_classes
            try:
                existing = collection.get(ids=[unique_id])
                if existing and len(existing.get('ids', [])) > 0:
                    # Obtener el valor anterior de dependent_classes
                    prev_dependent_classes = existing['metadatas'][0].get('dependent_classes', "")
                    
                    # Forzar que prev_dependent_classes sea string
                    if isinstance(prev_dependent_classes, list):
                        prev_dependent_classes = ",".join([str(c) for c in prev_dependent_classes])
                    elif not isinstance(prev_dependent_classes, str):
                        prev_dependent_classes = str(prev_dependent_classes)
                        
                    new_dependent_classes = str(prev_dependent_classes) + "," + str(dependent_classes)
                    
                    # Actualizar el registro en la colección
                    collection.update(
                        ids=[unique_id],
                        metadatas=[{
                            **existing['metadatas'][0],
                            "dependent_classes": new_dependent_classes
                        }]
                    )
                print(f"ID {unique_id} actualizado con dependent_classes: {new_dependent_classes}")
            except Exception as e:
                print(f"Error actualizando dependent_classes para ID {unique_id}: {e}")
                return jsonify({'error': 'Error updating dependent_classes'}), 500
            else:
                return jsonify({'message': 'ID already exists, but updated dependent_classes'}), 200
    except Exception as e:
        print(f"Error saving code: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/save_methods', methods=['POST'])
def save_methods():
    try:
        data = request.json
        class_name = data.get('class_name', '')
        methods = data.get('methods', [])

        if not class_name or not methods:
            return jsonify({'error': 'Missing class name or methods'}), 400

        for method in methods:
            method_name = method.get('method_name')
            signature = method.get('signature')
            code = method.get('code')
            comment = method.get('comment', '')
            annotations = method.get('annotations', {})
            dependent_methods = method.get('dependent_methods', [])

            if not method_name or not code:
                return jsonify({'error': 'Missing method name or code'}), 400

            # Verificar si ya existe
            unique_id = class_name + '-' + method_name
            try:
                existing = collection.get(ids=[unique_id])
                if existing and len(existing.get('ids', [])) > 0:
                    print(f"ID {unique_id} ya existe. Se omite.")
                    continue
            except Exception as e:
                print(f"Error al comprobar existencia de ID {unique_id}: {e}")
                
            embeddings = generate_embedding(code)
            
            collection.add(
                ids=[unique_id],
                embeddings=[embeddings],
                metadatas=[{
                    "class_name": class_name,
                    "method_name": method_name,
                    "signature": signature,
                    "code": code,
                    "comment": comment,
                    "annotations": annotations,
                    "dependent_methods": dependent_methods,
                    "is_constructor": method_name == class_name ## Constructor check
                }]
            )
        return jsonify({'message': 'Methods saved successfully'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500


def extract_method_name(code):
    # Regex to match method definitions in Java
    match = re.search(r'public\s+[a-zA-Z0-9_<>\[\]]+\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(', code)
    if match:
        return match.group(1)
    return None

        
@app.route('/search_similar_methods', methods=['POST'])
def search_similar_methods():
    try:
        data = request.json
        #print(f"Data received: {data}")
        test_class_name = data.get('test_class_name', '')
        class_name = data.get('class_name', '')
        method_name = data.get('method_name', '')
        code = data.get('code')

        # Extract method name from the provided code
        method_name_from_code = extract_method_name(code)
        
        if class_name == '' or method_name == '':
            print("Class name or method name is empty")
            class_name = 'Dependency'
            method_name = method_name_from_code
            #print("Code: ", code )
            print("Method name from code: ", method_name_from_code)
        else:
            print(f'Class: {class_name}, Method: {method_name}')
        
        max_neighbours = data.get('max_neighbours', 8)
        similarity_threshold = 0.75

        if not code:
            print("ERROR: Missing code")
            return jsonify({'error': 'Missing code'}), 400

        query_embedding = generate_embedding(code)

        # Búsqueda en la base de datos
        if test_class_name:
            try:
                results = collection.query(
                    query_embeddings=[query_embedding],
                    n_results=max_neighbours,  
                    include=["metadatas", "embeddings"],
                    where={"dependent_classes": {"$in": [test_class_name]}}
                )                  
            except Exception as e:
                print(f"Error retrieving similar methods: {e}")
                return jsonify({'error': 'Error retrieving similar methods', 'details': str(e)}), 500
        else:
            try:
                results = collection.query(
                    query_embeddings=[query_embedding],
                    n_results=max_neighbours,  
                    include=["metadatas", "embeddings"],
                )
            except Exception as e:
                print(f"Error retrieving similar methods: {e}")
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
                "dependent_methods": meta.get("dependent_methods", []),
                "dependent_classes": meta.get("dependent_classes", ""),
                "similarity": round(similarity, 2),
                "is_constructor": meta.get("is_constructor", False),
            })
            
            print(f"RAG: Similarity: {similarity}, Class: {meta.get('class_name')}, Method: {meta.get('method_name')}")

            if len(matched_results) >= max_neighbours:
                break

        if not matched_results:
            print("No similar methods found\n\n")
            return jsonify({'error': 'No similar methods found'}), 404
        else:
            return jsonify({'results': matched_results})

    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/count_elements', methods=['GET'])
def count_elements():
    try:
        try:
            dependent_class = request.args.get('dependent_class', '')
        except Exception as e:
            print("No se ha añadido clase dependiente, buscando elementos de la base de datos")
            results = collection.get(include=["metadatas"])
        else:
            results = collection.get(include=["metadatas"], where={"dependent_classes": {"$in": [dependent_class]}})
            
        total_elements = len(results["metadatas"])
        return jsonify({'total_elements': total_elements})
    
    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    print("Server is starting on http://127.0.0.1:5000")
    serve(app=app, host='127.0.0.1', port=5000, threads=10)

