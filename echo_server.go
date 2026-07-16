package main

import (
    "encoding/json"
    "fmt"
    "net/http"
    "strconv"
    "time"
)

func handler(w http.ResponseWriter, r *http.Request) {
    if delayStr := r.URL.Query().Get("delay"); delayStr != "" {
        if ms, err := strconv.Atoi(delayStr); err == nil && ms > 0 {
            time.Sleep(time.Duration(ms) * time.Millisecond)
        }
    }
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(map[string]string{
        "method": r.Method,
        "path":   r.URL.Path,
    })
}

func main() {
    http.HandleFunc("/", handler)
    fmt.Println("Echo server running on :8081")
    http.ListenAndServe(":8081", nil)
}