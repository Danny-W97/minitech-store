# Kubernetes Auth — przykład

Po włączeniu `auth/kubernetes` skonfiguruj OpenBao z adresem API Kubernetes i
CA klastra. Następnie utwórz rolę dla każdego serwisu, np.:

```bash
bao write auth/kubernetes/role/customer-service \
  bound_service_account_names=customer-service \
  bound_service_account_namespaces=minitech-store \
  policies=customer-service \
  ttl=15m
```

Powyższa komenda jest przykładem do wykonania przez operatora w klastrze; nie
uruchamiaj jej z sekretami zapisanymi w repozytorium. Tokeny ServiceAccount,
JWT klastra i certyfikaty CA są dostarczane przez Kubernetes.
