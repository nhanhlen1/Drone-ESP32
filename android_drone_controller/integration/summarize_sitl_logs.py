#!/usr/bin/env python3
"""Đọc DataFlash SITL đã chạy; cần pymavlink==2.4.49, không kết nối mạng/FC."""
import argparse,json,pathlib
from pymavlink import DFReader

def summarize(path):
    log=DFReader.DFReader_binary(str(path))
    rc=[];errors=[];armed=[]
    while True:
        message=log.recv_match(type=['RCIN','ERR','ARM'])
        if message is None:break
        data=message.to_dict()
        if message.get_type()=='RCIN':
            rc.append({'time_us':data.get('TimeUS'),'channels':[data.get('C'+str(i)) for i in range(1,5)]})
        elif message.get_type()=='ERR':errors.append(data)
        else:armed.append(data)
    unique=[];transitions=[]
    for event in rc:
        if not transitions or event['channels']!=transitions[-1]['channels']:transitions.append(event)
        if event['channels'] not in unique:unique.append(event['channels'])
    return {'file':str(path),'rcin_records':len(rc),'observed_ch1_to_ch4':unique,'rcin_transitions':transitions,
            'error_transitions':errors,'arm_records':armed,
            'interpretation':'DataFlash của process SITL tham chiếu, không phải log ESP32 thật. '
            'RCIN là RC input do firmware ghi; không chứng minh đầu ra motor hoặc bay.'}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('logs',nargs='+',type=pathlib.Path)
    p.add_argument('--output',type=pathlib.Path);args=p.parse_args()
    text=json.dumps([summarize(path) for path in args.logs],ensure_ascii=False,indent=2)
    if args.output:
        with args.output.open('x',encoding='utf-8') as report:report.write(text)
    else:print(text)
if __name__=='__main__':main()
