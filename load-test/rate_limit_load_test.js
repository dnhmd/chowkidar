import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 10,    // 10 virtual users firing simultaneously
    iterations: 20,  // total 20 requests across all VUs
                    // with capacity 5, we expect ~15 to be 429
};

export default function() {
    const res = http.get('http://localhost:8080/rate-test', {
        headers: { 'X-API-Key': '811ddf7a385e45e7bf99d6b01c181bea' }
    });

    check(res, {
        'either allowed or rate limited': (r) =>
            r.status === 200 || r.status === 429,
        'rate limit headers present': (r) =>
            r.headers['Ratelimit-Limit'] !== undefined,
    });
}