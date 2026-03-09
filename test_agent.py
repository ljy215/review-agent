import requests

url = "http://127.0.0.1:8000/api/review"

# 故意留空语言字段，测试自动侦测和 AST 分析
data = {
    "language": "",
    "code_content": """
    public class UserController {
        public void getUserInfo(String id) {
            System.out.println("Get user: " + id);
        }

        private boolean checkPermission() {
            return true;
        }
    }
    """
}

response = requests.post(url, json=data)
print("\n=== Agent 返回结果 ===")
print("识别出的语言:", response.json().get("language_detected"))
print("\nReview 意见:\n", response.json().get("review_result"))