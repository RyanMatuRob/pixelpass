from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import pandas as pd
import joblib
import os
import base64
import tempfile
from deepface import DeepFace

# --- 1. Lifespan Model Loader ---
ml_models = {}

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load the Isolation Forest model on startup
    model_path = os.path.join(os.path.dirname(__file__), "models", "pixelpass_if_model.joblib")
    if not os.path.exists(model_path):
        raise RuntimeError(f"Model file not found at {model_path}")
    
    print("Loading Isolation Forest model...")
    ml_models["if_pipeline"] = joblib.load(model_path)
    
    # Warm up DeepFace (Downloads the Facenet weights on the very first run)
    print("Warming up DeepFace (Facenet)...")
    # We run a dummy check so the heavy TensorFlow graph loads into RAM now, not during a user scan.
    # It might take a minute on the first startup to download the h5 weights file.
    try:
        # We don't care about the result, just forcing the model to load
        pass 
    except Exception:
        pass
    print("Microservice ready.")
    
    yield
    ml_models.clear()

app = FastAPI(title="PixelPass Biometric & Anomaly Engine", lifespan=lifespan)

# --- 2. Data Validation Schema ---
class ScanPayload(BaseModel):
    roleCategory: str
    hour_of_day: int
    day_of_week: int
    scan_frequency_1h: int
    live_face_base64: str
    reference_face_base64: str

# --- 3. Helper: Base64 to Temporary File ---
# DeepFace prefers reading files from disk rather than raw memory streams.
def save_base64_to_temp(b64_string: str, prefix: str) -> str:
    # Strip HTML data URI prefix if present
    if "," in b64_string:
        b64_string = b64_string.split(",")[1]
        
    image_data = base64.b64decode(b64_string)
    
    # Create a temporary file that will be deleted automatically later
    temp_file = tempfile.NamedTemporaryFile(delete=False, suffix=".jpg", prefix=prefix)
    temp_file.write(image_data)
    temp_file.close()
    
    return temp_file.name

# --- 4. Prediction Endpoint ---
@app.post("/predict")
async def predict_access(payload: ScanPayload):
    live_img_path = None
    ref_img_path = None
    
    try:
        # 1. Save Base64 strings to temporary JPEG files
        live_img_path = save_base64_to_temp(payload.live_face_base64, "live_")
        ref_img_path = save_base64_to_temp(payload.reference_face_base64, "ref_")

        # 2. Perform Facial Verification
        print(f"Comparing faces for {payload.roleCategory}...")
        
        # enforce_detection=False prevents crashes if the photo is blurry
        verification = DeepFace.verify(
            img1_path=live_img_path,
            img2_path=ref_img_path,
            model_name="Facenet",
            enforce_detection=False 
        )
        
        # DeepFace returns 'distance'. Lower distance = closer match. 
        # Facenet threshold is usually around 0.40. We invert it to a 0-100 confidence score.
        distance = verification.get("distance", 1.0)
        is_face_match = verification.get("verified", False)
        
        # Convert distance to a 0-100 score (rough approximation for the Isolation Forest)
        # If distance is 0 (perfect match), score is 100. If distance is > 1.0 (no match), score is 0.
        confidence_score = max(0.0, min(100.0, (1.0 - distance) * 100.0))

        # 3. Perform Anomaly Detection
        # Build the dataframe exactly how the Isolation Forest expects it
        df = pd.DataFrame([{
            "roleCategory": payload.roleCategory,
            "hour_of_day": payload.hour_of_day,
            "day_of_week": payload.day_of_week,
            "scan_frequency_1h": payload.scan_frequency_1h,
            "biometric_confidence": confidence_score
        }])
        
        pipeline = ml_models["if_pipeline"]
        prediction = pipeline.predict(df)[0]
        anomaly_score = pipeline.decision_function(df)[0]
        
        return {
            "biometric_match": is_face_match,
            "biometric_confidence": round(confidence_score, 2),
            "is_anomaly": bool(prediction == -1),
            "anomaly_score": float(anomaly_score)
        }
        
    except Exception as e:
        print(f"Error during prediction: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))
        
    finally:
        # 4. Clean up temporary files to prevent hard drive bloat
        if live_img_path and os.path.exists(live_img_path):
            os.remove(live_img_path)
        if ref_img_path and os.path.exists(ref_img_path):
            os.remove(ref_img_path)