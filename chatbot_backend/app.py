from flask import Flask, request, jsonify
import google.generativeai as genai

app = Flask(__name__)

# Replace with your Gemini API key
GEMINI_API_KEY = "AIzaSyDCgMfd0cw3I6fM5iY9W10Yptz3BBS3OgQ"
genai.configure(api_key=GEMINI_API_KEY)

# Initialize the Gemini model
model = genai.GenerativeModel('gemini-1.5-flash')

@app.route('/chat', methods=['POST'])
def chat():
    data = request.get_json()
    user_text = data.get('text', '')
    language = data.get('language', 'en')

    if not user_text:
        return jsonify({'response': 'Please provide a message'}), 400

    try:
        # Create a prompt that includes the language context
        prompt = f"Respond to the following message in {language}: {user_text}"
        response = model.generate_content(prompt)
        bot_text = response.text.strip()

        return jsonify({'response': bot_text})
    except Exception as e:
        return jsonify({'response': f"Error: {str(e)}"}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)