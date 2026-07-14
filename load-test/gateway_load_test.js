import http from 'k6/http';
import { check } from 'k6';

export const options = {
    stages: [
        { duration: '30s', target: 50 },  // Warm up to 50 users
        { duration: '1m', target: 200 },  // Ramp up to a heavy 200 users
        { duration: '1m', target: 200 },  // Sustained peak load
        { duration: '30s', target: 0 },   // Cool down
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'], // Target: 95% of gateway requests under 500ms
        http_req_failed: ['rate<0.01'],   // Critical: Less than 1% failure rate
    }
};

export default function() {
    const res = http.get('http://localhost:8080/echo', {
        headers: { 'X-API-Key': '811ddf7a385e45e7bf99d6b01c181bea' }
    });

    check(res, {
        'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
        'has rate limit headers': (r) => r.headers['Ratelimit-Limit'] !== undefined,
    });

    // REMOVED sleep(0.1) to hammer the gateway as fast as VUs can process
}