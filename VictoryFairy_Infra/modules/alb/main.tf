# alb 모듈: AWS Load Balancer Controller(LBC) 용 IRSA — 파드 단위 최소 권한(SKILL §4).
#
# 경계: 이 모듈은 컨트롤러가 ALB/TargetGroup 을 만들 IAM 권한(역할+정책)만 만든다.
#   컨트롤러 파드 자체는 Helm 으로 설치하며(클러스터 인증 필요, Terraform 밖),
#   Helm 의 serviceAccount.annotations["eks.amazonaws.com/role-arn"] 에 이 역할 ARN 을 지정한다.
#   Ingress(k8s/22-ingress.yaml)가 ingressClassName: alb 로 이 컨트롤러에 ALB 프로비저닝을 위임한다.
# 발견: LBC 는 퍼블릭 서브넷의 kubernetes.io/role/elb=1 태그(network 모듈)로 서브넷을 자동 탐색한다.

# 이 SA(kube-system/aws-load-balancer-controller) 토큰만 이 역할을 맡을 수 있다(IRSA).
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.service_account_namespace}:${var.service_account_name}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = "${var.name_prefix}-aws-lbc"
  assume_role_policy = data.aws_iam_policy_document.assume.json

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-aws-lbc"
  })
}

# 정책 본문은 kubernetes-sigs/aws-load-balancer-controller v3.4.2 의 공식 iam_policy.json 사본.
#   출처: https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v3.4.2/docs/install/iam_policy.json
#   ⚠ 설치되는 컨트롤러(Helm 차트) 버전과 반드시 일치시킬 것 — 불일치 시 AccessDenied 로 ALB 프로비저닝 실패.
#     (Helm 이 설치한 이미지 태그 = `kubectl -n kube-system get deploy aws-load-balancer-controller -o jsonpath='{..image}'`)
resource "aws_iam_policy" "this" {
  name        = "${var.name_prefix}-aws-lbc"
  description = "AWS Load Balancer Controller (v3.4.2) 권한 — ELBv2/TargetGroup/SG 관리."
  policy      = file("${path.module}/iam_policy.json")

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-aws-lbc"
  })
}

resource "aws_iam_role_policy_attachment" "this" {
  role       = aws_iam_role.this.name
  policy_arn = aws_iam_policy.this.arn
}
