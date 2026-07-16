package main

import (
    "encoding/json"
    "fmt"
    "io"
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

    var bodyString string
    if r.Body != nil {
    	bodyBytes, err := io.ReadAll(r.Body)
        if err == nil {
    	    bodyString = string(bodyBytes)
    	}
    	r.Body.Close()
    }

    responsePayload := map[string]any{
        "method":        r.Method,
    	"path":          r.URL.Path,
    	"host":          r.Host,
    	"remote_addr":   r.RemoteAddr,
    	"proto":         r.Proto,
    	"content_length": r.ContentLength,
    	"query_params":  r.URL.Query(),
    	"headers":       r.Header,
    	"body":          bodyString,
    }

    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(responsePayload)
}

func main() {
	http.HandleFunc("/", handler)
	fmt.Println("Echo server running on :8081")
	http.ListenAndServe(":8081", nil)
}