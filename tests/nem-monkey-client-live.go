// Tests MonkeyCode's real pkg/llm component, not the full MonkeyCode platform.
// Run inside the official MonkeyCode backend module at the recorded revision.
package main

import (
 "context"
 "fmt"
 "net/http"
 "strings"
 "time"
 "github.com/chaitin/MonkeyCode/backend/pkg/llm"
)
type agentTransport struct{ base http.RoundTripper }
func (t agentTransport) RoundTrip(r *http.Request)(*http.Response,error){
 r = r.Clone(r.Context()); r.Header.Set("X-NemApi-Agent","monkey-code"); return t.base.RoundTrip(r)
}
func main(){
 client:=&http.Client{Timeout: 180*time.Second,Transport:agentTransport{http.DefaultTransport}}
 for _,kind:=range []llm.InterfaceType{llm.InterfaceOpenAIChat,llm.InterfaceOpenAIResponses} {
  cfg:=llm.Config{BaseURL:"http://127.0.0.1:8090/v1",APIKey:"not-required",Model:"deepseek-chat",InterfaceType:kind,HTTPClient:client}
  if err:=llm.HealthCheck(context.Background(),cfg);err!=nil{panic(fmt.Sprintf("HEALTH %s: %v",kind,err))}
  fmt.Println("MONKEY_LLM_HEALTH_OK",kind)
  resp,err:=llm.NewClient(cfg).Chat(context.Background(),llm.ChatRequest{System:"Follow the user's instruction literally.",Messages:[]llm.Message{{Role:"user",Content:"Reply with exactly MONKEY_GO_café"}},MaxTokens:100})
  if err!=nil{panic(fmt.Sprintf("CHAT %s: %v",kind,err))}
  if !strings.Contains(resp.Content,"MONKEY_GO_café"){panic(fmt.Sprintf("Unexpected %s: %q",kind,resp.Content))}
  if resp.Usage.TotalTokens<=0 {panic("missing usage")}
  fmt.Println("MONKEY_LLM_CHAT_OK",kind)
 }
}
