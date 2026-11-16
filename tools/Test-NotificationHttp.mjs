import assert from 'node:assert/strict';
import { demoTokens } from './lib/KeycloakDemo.mjs';
const base=process.env.TICKETFLOW_NOTIFICATION_URL??'http://localhost:8085';
const url=new URL(base);assert.equal(url.protocol,'http:');assert.ok(['localhost','127.0.0.1'].includes(url.hostname));
const tokens=await demoTokens();
async function check(method,path,status,token){
  const response=await fetch(base+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),'X-Roles':'ADMIN','X-Customer-Id':'999999'},signal:AbortSignal.timeout(15000)});
  assert.equal(response.status,status,`${method} ${path}`);console.log(`OK Notification ${status} ${method} ${path}`);return response;
}
await check('GET','/actuator/health',200);
await check('GET','/api/notifications/me',401);
await check('GET','/api/notifications/me',403,tokens.get('demo-organizer'));
const own=await check('GET','/api/notifications/me',200,tokens.get('demo-customer'));assert.deepEqual(await own.json(),[]);
await check('GET','/api/notifications',403,tokens.get('demo-customer'));
await check('GET','/api/notifications',200,tokens.get('demo-admin'));
await check('GET','/api/notifications/quarantine',200,tokens.get('demo-admin'));
await check('POST','/api/notifications',403,tokens.get('demo-admin'));
await check('GET','/actuator/env',403,tokens.get('demo-admin'));
console.log('Notification real JWT demo passed; no synthetic business notification created in runtime.');
