import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';

export default function VisitorEnrollment() {
  const { token } = useParams();
  
  const [step, setStep] = useState(1); // 1 = Upload, 2 = Success (Show QR)
  const [previewImage, setPreviewImage] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [qrData, setQrData] = useState(null);
  const [validUntil, setValidUntil] = useState(null);
  const [error, setError] = useState(null);

  const handleFileSelect = (event) => {
    const file = event.target.files[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (e) => setPreviewImage(e.target.result);
      reader.readAsDataURL(file);
    }
  };

  const handleSubmit = async () => {
    if (!previewImage) return;
    setIsSubmitting(true);
    setError(null);

    try {
      const response = await fetch(`http://localhost:3000/api/visitors/enroll/${token}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ referenceFaceBase64: previewImage })
      });

      const data = await response.json();

      if (response.ok) {
        setQrData(data.qrToken);
        setValidUntil(new Date(data.validUntil).toLocaleString());
        setStep(2);
      } else {
        setError(data.error || "Enrollment failed. Please contact the administrator.");
      }
    } catch (err) {
      setError("Network error. Please check your connection.");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md bg-white rounded-2xl shadow-xl overflow-hidden">
        
        {/* Header */}
        <div className="bg-gray-900 px-6 py-8 text-center">
          <h1 className="text-2xl font-black text-blue-400">PixelPass</h1>
          <p className="text-gray-400 text-sm mt-1">Visitor Access Portal</p>
        </div>

        <div className="p-8 text-center">
          {error && (
            <div className="bg-red-50 text-red-700 p-3 rounded-lg text-sm mb-6 font-medium border border-red-200">
              {error}
            </div>
          )}

          {step === 1 && (
            <div className="animate-in fade-in zoom-in duration-300">
              <h2 className="text-xl font-bold text-gray-900 mb-2">Selfie Verification</h2>
              <p className="text-gray-500 text-sm mb-6">
                Please take a clear photo of your face. This will be used to verify your identity at the security gate.
              </p>

              <div className="w-48 h-48 mx-auto bg-gray-100 rounded-full mb-6 flex items-center justify-center overflow-hidden border-4 border-dashed border-gray-300 relative group">
                {previewImage ? (
                  <img src={previewImage} alt="Selfie" className="w-full h-full object-cover" />
                ) : (
                  <span className="text-gray-400 font-medium text-sm">Tap to Camera</span>
                )}
                <input 
                  type="file" 
                  accept="image/*" 
                  capture="user" 
                  onChange={handleFileSelect}
                  className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
                />
              </div>

              <button 
                onClick={handleSubmit}
                disabled={!previewImage || isSubmitting}
                className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white font-bold py-3 px-4 rounded-xl shadow transition-colors"
              >
                {isSubmitting ? 'Generating Pass...' : 'Generate My QR Code'}
              </button>
            </div>
          )}

          {step === 2 && (
            <div className="animate-in fade-in zoom-in duration-300">
              <div className="w-16 h-16 bg-green-100 text-green-600 rounded-full flex items-center justify-center mx-auto mb-4 text-3xl">
                ✓
              </div>
              <h2 className="text-xl font-bold text-gray-900 mb-2">Access Granted</h2>
              <p className="text-gray-500 text-sm mb-6">
                Present this QR code and look at the camera at the security terminal.
              </p>

              <div className="inline-block bg-white p-4 rounded-xl border-2 border-gray-200 shadow-sm mb-6">
                <QRCodeSVG value={qrData} size={200} level="H" />
              </div>

              <div className="bg-blue-50 text-blue-800 p-4 rounded-xl text-sm font-medium border border-blue-100">
                Valid until:<br/>{validUntil}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}