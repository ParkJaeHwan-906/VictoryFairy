# prod 환경

`dev/` 와 동일한 파일 구성(terraform.tf / providers.tf / main.tf / variables.tf /
outputs.tf / terraform.tfvars)을 따릅니다. dev 에서 구성이 안정화된 뒤 복제하고,
차이는 **코드가 아니라 변수 값(tfvars)** 으로만 표현합니다.

주의:
- 백엔드 `key` 를 `prod/terraform.tfstate` 로 분리합니다.
- 노드그룹 min/desired/max, 인스턴스 타입 등을 prod 규모로 조정합니다.
