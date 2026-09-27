import concurrent.futures
import json
import re
from collect import fetch,query,OUT
BASE='https://mapy.geoportal.gov.pl/wss/service/PZGIK/FOTO/WMS/ModeleBudynkow3D'
def info(item):
    label,lon,lat=item
    layers='Modele_3D_budynkow_LoD1_2024,Modele_3D_budynkow_LoD1_2022'
    url=query(BASE,{'service':'WMS','version':'1.1.1','request':'GetFeatureInfo','layers':layers,'query_layers':layers,'styles':'','srs':'EPSG:4326','bbox':f'{lon-.002},{lat-.002},{lon+.002},{lat+.002}','width':101,'height':101,'x':50,'y':50,'format':'image/png','info_format':'text/html','feature_count':10})
    text=fetch(f'lod1-info-{label}.html',url).decode('utf-8','replace')
    print(label,text[:6500])
if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(info,[('start',22.284,52.1666),('end',22.303,52.1808),('county',22.304986519409706,52.18008147590834)]))
