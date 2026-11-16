import assert from 'node:assert/strict';
import { demoTokens } from './lib/KeycloakDemo.mjs';
import { setTimeout as delay } from 'node:timers/promises';
const viaGateway=process.argv.includes('--gateway');
const base=viaGateway?(process.env.TICKETFLOW_GATEWAY_URL??'http://localhost:8080'):(process.env.TICKETFLOW_NOTIFICATION_URL??'http://localhost:8085');
const url=new URL(base);assert.equal(url.protocol,'http:');assert.ok(['localhost','127.0.0.1'].includes(url.hostname));
const tokens=await demoTokens();
async function check(method,path,status,token){
  const response=await fetch(base+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),'X-Roles':'ADMIN','X-Customer-Id':'999999'},signal:AbortSignal.timeout(15000)});
  assert.equal(response.status,status,`${method} ${path}`);console.log(`OK Notification ${status} ${method} ${path}`);return response;
}
await check('GET','/actuator/health',200);
await check('GET','/api/notifications/me',401);
await check('GET','/api/notifications/me',403,tokens.get('demo-organizer'));
if(viaGateway){
  const deadline=Date.now()+180000;
  while(true){
    const response=await fetch(base+'/api/notifications/me',{headers:{Authorization:`Bearer ${tokens.get('demo-customer')}`},signal:AbortSignal.timeout(15000)});
    if(response.status===200)break;
    assert.equal(response.status,503,'Discovery readiness must not hide authentication/profile errors');
    assert.ok(Date.now()<deadline,'Notification discovery did not converge');await delay(3000);
  }
}
const own=await check('GET','/api/notifications/me',200,tokens.get('demo-customer'));assert.deepEqual(await own.json(),[]);
await check('GET','/api/notifications',403,tokens.get('demo-customer'));
await check('GET','/api/notifications',viaGateway?403:200,tokens.get('demo-admin'));
await check('GET','/api/notifications/quarantine',viaGateway?403:200,tokens.get('demo-admin'));
await check('GET','/api/notifications/578b6035-0a61-44e0-ad88-f492362e5fbc',404,tokens.get('demo-customer'));
await check('POST','/api/notifications',403,tokens.get('demo-admin'));
await check('GET','/actuator/env',403,tokens.get('demo-admin'));
console.log('Notification real JWT demo passed; no synthetic business notification created in runtime.');
