import request from '@/utils/request'
export const modules=()=>request({url:'/mes/modules'})
export const list=(resource,params)=>request({url:'/mes/master/'+resource,params})
export const options=resource=>request({url:'/mes/master/'+resource+'/options'})
export const create=(resource,data)=>request({url:'/mes/master/'+resource,method:'post',data})
export const update=(resource,id,data)=>request({url:'/mes/master/'+resource+'/'+id,method:'put',data})
export const remove=(resource,id,version)=>request({url:'/mes/master/'+resource+'/'+id,method:'delete',params:{version}})
export const processes=()=>request({url:'/mes/master/processes/list'})
