import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from './api';

type Page='overview'|'products'|'stock'|'warehouses'|'suppliers'|'purchases'|'sales'|'customers'|'reports'|'users';
type Row=Record<string,any>;

@Component({selector:'app-root',standalone:true,imports:[CommonModule,FormsModule,CurrencyPipe,DatePipe],templateUrl:'./app.component.html'})
export class AppComponent implements OnInit {
  private api=inject(Api);
  token=sessionStorage.getItem('stockpilot_token')||'';
  email=sessionStorage.getItem('stockpilot_email')||'owner@stockpilot.local'; password=''; role=sessionStorage.getItem('stockpilot_role')||'';
  resetMode:'login'|'request'|'confirm'='login';resetToken='';newPassword='';
  page:Page='overview';busy=false;error='';notice='';search='';modal='';form:Row={};
  products:Row[]=[];stock:Row[]=[];warehouses:Row[]=[];suppliers:Row[]=[];purchases:Row[]=[];sales:Row[]=[];customers:Row[]=[];users:Row[]=[];report:Row[]=[];auditEvents:Row[]=[];
  reportName='low-stock';selectedPurchase:Row|null=null;selectedSale:Row|null=null;selectedSupplier:Row|null=null;
  nav:{page:Page;icon:string;label:string}[]=[
    {page:'overview',icon:'◫',label:'Overview'},{page:'products',icon:'◈',label:'Products'},{page:'stock',icon:'▤',label:'Inventory'},
    {page:'warehouses',icon:'▣',label:'Warehouses'},{page:'suppliers',icon:'◇',label:'Suppliers'},{page:'purchases',icon:'↙',label:'Purchasing'},
    {page:'sales',icon:'↗',label:'Sales orders'},{page:'customers',icon:'♧',label:'Customers'},{page:'reports',icon:'▥',label:'Reports'},{page:'users',icon:'◉',label:'Team access'}];
  async ngOnInit(){if(this.token)await this.refresh();}
  async run(action:()=>Promise<any>,message='Saved'){this.busy=true;this.error='';this.notice='';try{await action();this.notice=message;await this.refresh();if(this.selectedPurchase&&this.page==='purchases')await this.selectPurchase(this.selectedPurchase.id);if(this.selectedSale&&this.page==='sales')await this.selectSale(this.selectedSale.id);this.modal='';}catch(e:any){this.error=e?.error?.error||e?.message||'Request failed. Please try again.';}finally{this.busy=false;}}
  async login(){await this.run(async()=>{const session=await this.api.post('/auth/login',{email:this.email,password:this.password});this.token=session.token;this.role=session.role;this.email=session.email;sessionStorage.setItem('stockpilot_token',this.token);sessionStorage.setItem('stockpilot_role',this.role);sessionStorage.setItem('stockpilot_email',this.email);this.password='';},'Welcome to StockPilot');}
  async logout(){try{await this.api.post('/auth/logout',{});}finally{sessionStorage.clear();this.token='';this.role='';this.page='overview';}}
  async requestReset(){await this.run(()=>this.api.post('/auth/reset/request',{email:this.email}),'If this account exists, a reset token was emailed.');this.resetMode='confirm';}
  async confirmReset(){await this.run(()=>this.api.post('/auth/reset/confirm',{token:this.resetToken,password:this.newPassword}),'Password updated. Sign in with the new password.');this.newPassword='';this.resetToken='';this.resetMode='login';}
  async refresh(){if(!this.token)return;try{const data=await Promise.all([this.api.get('/products?search='+encodeURIComponent(this.search)),this.api.get('/stock'),this.api.get('/warehouses'),this.api.get('/suppliers'),this.api.get('/purchases'),this.api.get('/sales'),this.api.get('/customers')]);[this.products,this.stock,this.warehouses,this.suppliers,this.purchases,this.sales,this.customers]=data;if(this.role==='OWNER'){this.users=await this.api.get('/users');this.auditEvents=await this.api.get('/users/audit');}if(this.page==='reports')this.report=await this.api.get('/reports/'+this.reportName);}catch(e:any){if(e?.status===401){sessionStorage.clear();this.token='';}this.error=e?.error?.error||'Could not load data';}}
  async navigate(page:Page){this.page=page;this.error='';this.notice='';if(page==='reports')await this.loadReport();}
  async loadReport(){this.report=await this.api.get('/reports/'+this.reportName);}
  get totalUnits(){return this.stock.reduce((n,r)=>n+Number(r.on_hand||0),0);}
  get availableUnits(){return this.stock.reduce((n,r)=>n+Number(r.available||0),0);}
  get openPurchases(){return this.purchases.filter(r=>['SUBMITTED','PARTIALLY_RECEIVED'].includes(r.status)).length;}
  get activeSales(){return this.sales.filter(r=>!['FULFILLED','CANCELLED'].includes(r.status)).length;}
  get lowStock(){return this.lowStockRows.length;}
  get lowStockRows(){return this.products.filter(p=>p.variant_id && Number(this.stock.filter(b=>b.variant_id===p.variant_id).reduce((n,b)=>n+Number(b.available),0))<=Number(p.reorder_threshold)).slice(0,6);}
  open(kind:string,values:Row={}){this.modal=kind;this.form={...values};this.error='';}
  async save(){const f=this.form;switch(this.modal){
    case 'product':return this.run(()=>this.api.post('/products',{name:f.name,categoryId:null,unit:f.unit||'EACH',reorderThreshold:Number(f.reorderThreshold||0)}),'Product created');
    case 'variant':return this.run(()=>this.api.post('/variants',{productId:Number(f.productId),sku:f.sku,barcode:f.barcode||null,label:f.label||'Standard',sellingPrice:Number(f.sellingPrice),purchaseCost:Number(f.purchaseCost)}),'Variant created');
    case 'warehouse':return this.run(()=>this.api.post('/warehouses',{name:f.name,code:f.code}),'Warehouse created');
    case 'location':return this.run(()=>this.api.post('/locations',{warehouseId:Number(f.warehouseId),code:f.code}),'Location created');
    case 'supplier':return this.run(()=>this.api.post('/suppliers',{name:f.name,email:f.email,phone:f.phone,leadDays:Number(f.leadDays||7)}),'Supplier created');
    case 'customer':return this.run(()=>this.api.post('/customers',{name:f.name,email:f.email,phone:f.phone,address:f.address}),'Customer created');
    case 'adjust':return this.run(()=>this.api.post('/stock/adjustments',{variantId:Number(f.variantId),locationId:Number(f.locationId),delta:Number(f.delta),reason:f.reason,idempotencyKey:crypto.randomUUID()}),'Stock adjusted');
    case 'transfer':return this.run(()=>this.api.post('/stock/transfers',{variantId:Number(f.variantId),sourceLocationId:Number(f.sourceLocationId),targetLocationId:Number(f.targetLocationId),quantity:Number(f.quantity),idempotencyKey:crypto.randomUUID()}),'Transfer completed');
    case 'purchase':return this.run(()=>this.api.post('/purchases',{supplierId:Number(f.supplierId),expectedAt:f.expectedAt||null,lines:[{variantId:Number(f.variantId),quantity:Number(f.quantity),unitCost:Number(f.unitCost)}]}),'Purchase order created');
    case 'receipt':return this.run(()=>this.api.post(`/purchases/${f.orderId}/receipts`,{externalRef:f.externalRef,lines:[{purchaseLineId:Number(f.lineId),locationId:Number(f.locationId),quantity:Number(f.quantity)}]}),'Goods received');
    case 'sale':return this.run(()=>this.api.post('/sales',{customerId:Number(f.customerId),lines:[{variantId:Number(f.variantId),quantity:Number(f.quantity)}]}),'Sales order created');
    case 'reserve':return this.run(()=>this.api.post(`/sales/${f.orderId}/reserve`,{allocations:this.selectedSale?.lines.map((line:Row)=>({lineId:line.id,locationId:Number(f.locationId)}))}),'Stock reserved');
    case 'fulfill':return this.run(()=>this.api.post(`/sales/${f.orderId}/fulfill`,{lineIds:[Number(f.lineId)],trackingNumber:f.trackingNumber}),'Shipment created');
    case 'return':return this.run(()=>this.api.post(`/sales/${f.orderId}/returns`,{lineId:Number(f.lineId),locationId:Number(f.locationId),quantity:Number(f.quantity),reason:f.reason,idempotencyKey:crypto.randomUUID()}),'Return received');
    case 'user':return this.run(()=>this.api.post('/users',{email:f.email,password:f.password,role:f.role}),'Employee created');
  }}
  async action(path:string,question:string){if(!confirm(question))return;await this.run(()=>this.api.post(path,{}),'Status updated');if(this.selectedPurchase)await this.selectPurchase(this.selectedPurchase.id);if(this.selectedSale)await this.selectSale(this.selectedSale.id);}
  async selectPurchase(id:number){this.selectedPurchase=await this.api.get('/purchases/'+id);this.selectedSale=null;}
  async selectSale(id:number){this.selectedSale=await this.api.get('/sales/'+id);this.selectedPurchase=null;}
  async selectSupplier(id:number){this.selectedSupplier=await this.api.get('/suppliers/'+id);}
  async toggleVariant(p:Row){if(!confirm(`${p.variant_active?'Discontinue':'Activate'} ${p.sku}?`))return;await this.run(()=>this.api.patch(`/variants/${p.variant_id}/status`,{active:!p.variant_active}),'Variant status updated');}
  async shipmentStatus(s:Row,status:string){if(!confirm(`Set shipment #${s.id} to ${status.replaceAll('_',' ')}?`))return;await this.run(()=>this.api.patch(`/sales/shipments/${s.id}/status`,{status}),'Shipment updated');if(this.selectedSale)await this.selectSale(this.selectedSale.id);}
  async upload(event:Event){const input=event.target as HTMLInputElement;const file=input.files?.[0];if(!file)return;const form=new FormData();form.append('file',file);await this.run(()=>this.api.post('/products/import',form),'CSV imported');input.value='';}
  async export(format:'csv'|'pdf'){await this.api.download(`/reports/${this.reportName}.${format}`,`${this.reportName}.${format}`);}
  async revoke(user:Row){if(!confirm(`Revoke access for ${user.email}?`))return;await this.run(()=>this.api.patch(`/users/${user.id}/status`,{status:'REVOKED'}),'Access revoked');}
  canManage(){return this.role!=='AUDITOR';}
}
