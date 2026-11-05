import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

@Injectable({providedIn:'root'})
export class Api {
  private http=inject(HttpClient);
  get<T=any>(path:string):Promise<T>{return firstValueFrom(this.http.get<T>('/api'+path));}
  post<T=any>(path:string,body:any):Promise<T>{return firstValueFrom(this.http.post<T>('/api'+path,body));}
  patch<T=any>(path:string,body:any):Promise<T>{return firstValueFrom(this.http.patch<T>('/api'+path,body));}
  async download(path:string,filename:string){const blob=await firstValueFrom(this.http.get('/api'+path,{responseType:'blob'}));const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=filename;a.click();URL.revokeObjectURL(url);}
}
