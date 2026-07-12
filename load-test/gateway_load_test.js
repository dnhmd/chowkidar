import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 10,        // virtual users
    duration: '30s', // test duration
    thresholds: {
        http_req_duration: ['p(95)<500'], // 95% of requests under 500ms
        http_req_failed: ['rate<0.01'],     // less than 1% failure rate
    }
};

export default function() {
    const res = http.get('http://localhost:8080/echo', {
        headers: { 'X-API-Key': '811ddf7a385e45e7bf99d6b01c181bea' }
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has rate limit headers': (r) => r.headers['Ratelimit-Limit'] !== undefined,
        'response time ok': (r) => r.timings.duration < 500,
    });

    sleep(0.1);
}