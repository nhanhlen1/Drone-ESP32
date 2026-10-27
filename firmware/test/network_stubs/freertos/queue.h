#pragma once
#include <deque>
#include <vector>
#include <cstring>
struct TestQueue {size_t capacity,size;std::deque<std::vector<unsigned char>> data;};
using QueueHandle_t=TestQueue*;
inline QueueHandle_t xQueueCreate(size_t capacity,size_t size) {return new TestQueue{capacity,size,{}};}
inline void vQueueDelete(QueueHandle_t q) {delete q;}
inline void xQueueReset(QueueHandle_t q) {q->data.clear();}
inline size_t uxQueueSpacesAvailable(QueueHandle_t q) {return q->capacity-q->data.size();}
inline int xQueueSend(QueueHandle_t q,const void* value,int) {if(q->data.size()==q->capacity)return 0;const auto* p=static_cast<const unsigned char*>(value);q->data.emplace_back(p,p+q->size);return 1;}
inline int xQueueSendToFront(QueueHandle_t q,const void* value,int) {if(q->data.size()==q->capacity)return 0;const auto* p=static_cast<const unsigned char*>(value);q->data.emplace_front(p,p+q->size);return 1;}
inline int xQueueReceive(QueueHandle_t q,void* output,int) {if(q->data.empty())return 0;memcpy(output,q->data.front().data(),q->size);q->data.pop_front();return 1;}
