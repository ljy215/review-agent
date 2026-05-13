import json

import requests

url = "http://127.0.0.1:8000/api/project_review"

data = {
    "task": "code_review",
    "files": [
        {
            "path": "/tmp/UserController.java",
            "name": "UserController.java",
            "language": "java",
            "content": """
public class UserController {
    public void getUserInfo(String id) {
        System.out.println("Get user: " + id);
    }

    private boolean checkPermission() {
        return true;
    }
}
""".strip(),
            "chunks": [
                {
                    "chunk_id": "UserController.java:class:1-8",
                    "kind": "class",
                    "start_line": 1,
                    "end_line": 8,
                    "estimated_tokens": 120,
                    "text": """
public class UserController {
    public void getUserInfo(String id) {
        System.out.println("Get user: " + id);
    }

    private boolean checkPermission() {
        return true;
    }
}
""".strip(),
                }
            ],
        }
    ],
    "standard_library_hits": [
        {
            "rule_id": "ARCH-001",
            "title": "Service layer should validate permission before user lookup",
            "dimension": "architecture",
            "content": "Sensitive controller methods must call authorization checks before business access.",
        }
    ],
}

response = requests.post(url, json=data, timeout=60)
print(json.dumps(response.json(), ensure_ascii=False, indent=2))
