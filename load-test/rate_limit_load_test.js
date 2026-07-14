import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 100,         // 100 concurrent attackers/users
    duration: '1m',   // Flooding for a full minute
    thresholds: {
        // Ensure the rate limiter itself doesn't crash the gateway with 500 errors
        http_req_failed: ['rate<0.01'],
    }
};

export default function() {
    const res = http.get('http://localhost:8080/rate-test', {
        headers: { 'X-API-Key': '811ddf7a385e45e7bf99d6b01c181bea' }
    });

    check(res, {
        'responded with 200 or 429': (r) => r.status === 200 || r.status === 429,
        'rate limit headers present': (r) => r.headers['Ratelimit-Limit'] !== undefined,
    });
}