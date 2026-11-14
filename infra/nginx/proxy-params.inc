proxy_pass $upstream;
proxy_connect_timeout 1s;
proxy_read_timeout 10s;
proxy_next_upstream error timeout http_502 http_503;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header Idempotency-Key $http_idempotency_key;
