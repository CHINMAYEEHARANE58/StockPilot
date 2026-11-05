import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, {providers:[provideHttpClient(withInterceptors([(request,next)=>{
  const token=sessionStorage.getItem('stockpilot_token');
  return next(token?request.clone({setHeaders:{Authorization:`Bearer ${token}`}}):request);
}]))]}).catch(console.error);
