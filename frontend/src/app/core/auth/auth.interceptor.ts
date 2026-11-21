import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';

import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/') || request.headers.has('Authorization')) {
    return next(request);
  }

  const auth = inject(AuthService);

  return from(auth.getAccessToken()).pipe(
    switchMap((token) => {
      if (!token) {
        return next(request);
      }

      return next(request.clone({
        setHeaders: { Authorization: `Bearer ${token}` },
      }));
    }),
  );
};
