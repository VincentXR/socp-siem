resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = var.github_oidc_thumbprints
}

data "aws_iam_policy_document" "github_plan_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:pull_request"]
    }
  }
}

data "aws_iam_policy_document" "github_apply_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:environment:${var.infrastructure_environment}"
      ]
    }
  }
}

data "aws_iam_policy_document" "github_release_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        for environment in var.release_environments :
        "repo:${var.github_repository}:environment:${environment}"
      ]
    }
  }
}

resource "aws_iam_role" "terraform_plan" {
  name                 = "${local.name_prefix}-terraform-plan"
  assume_role_policy   = data.aws_iam_policy_document.github_plan_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role" "terraform_apply" {
  name                 = "${local.name_prefix}-terraform-apply"
  assume_role_policy   = data.aws_iam_policy_document.github_apply_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role" "release" {
  name                 = "${local.name_prefix}-release"
  assume_role_policy   = data.aws_iam_policy_document.github_release_trust.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "state_read_lock_access" {
  statement {
    sid       = "LocateStateBucket"
    effect    = "Allow"
    actions   = ["s3:GetBucketLocation"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.state_bucket_name}"]
  }

  statement {
    sid       = "ListStatePrefix"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.state_bucket_name}"]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [var.state_object_key, "${var.state_object_key}.tflock"]
    }
  }

  statement {
    sid     = "ReadState"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    resources = [
      "arn:${data.aws_partition.current.partition}:s3:::${var.state_bucket_name}/${var.state_object_key}"
    ]
  }

  statement {
    sid    = "ReadWriteDeleteStateLock"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject"
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:s3:::${var.state_bucket_name}/${var.state_object_key}.tflock"
    ]
  }

  statement {
    sid    = "UseStateKey"
    effect = "Allow"
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:GenerateDataKey"
    ]
    resources = [var.state_kms_key_arn]
  }
}

data "aws_iam_policy_document" "state_write_access" {
  statement {
    sid     = "WriteState"
    effect  = "Allow"
    actions = ["s3:PutObject"]
    resources = [
      "arn:${data.aws_partition.current.partition}:s3:::${var.state_bucket_name}/${var.state_object_key}"
    ]
  }
}

data "aws_iam_policy_document" "terraform_plan" {
  source_policy_documents = [data.aws_iam_policy_document.state_read_lock_access.json]

  statement {
    sid    = "ReadInfrastructure"
    effect = "Allow"
    actions = [
      "budgets:ViewBudget",
      "ec2:Describe*",
      "ecr:Describe*",
      "ecr:Get*",
      "ecr:List*",
      "eks:Describe*",
      "eks:List*",
      "iam:Get*",
      "iam:List*",
      "kms:DescribeKey",
      "kms:GetKeyRotationStatus",
      "kms:GetKeyPolicy",
      "kms:ListAliases",
      "kms:ListResourceTags",
      "logs:Describe*",
      "logs:List*",
      "sts:GetCallerIdentity"
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "terraform_plan" {
  name   = "${local.name_prefix}-terraform-plan"
  role   = aws_iam_role.terraform_plan.id
  policy = data.aws_iam_policy_document.terraform_plan.json
}

data "aws_iam_policy_document" "terraform_apply" {
  source_policy_documents = [
    data.aws_iam_policy_document.state_read_lock_access.json,
    data.aws_iam_policy_document.state_write_access.json
  ]

  statement {
    sid    = "ManageDemoInfrastructure"
    effect = "Allow"
    actions = [
      "budgets:*",
      "ec2:*",
      "ecr:*",
      "eks:*",
      "kms:*",
      "logs:*",
      "sts:GetCallerIdentity"
    ]
    resources = ["*"]
  }

  statement {
    sid    = "ManagePrefixedRoles"
    effect = "Allow"
    actions = [
      "iam:AttachRolePolicy",
      "iam:CreateRole",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:DetachRolePolicy",
      "iam:GetRole",
      "iam:GetRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListRolePolicies",
      "iam:PassRole",
      "iam:PutRolePolicy",
      "iam:TagRole",
      "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy",
      "iam:UpdateRole"
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/${local.name_prefix}-*"
    ]
  }

  statement {
    sid    = "ManageGitHubOIDCProvider"
    effect = "Allow"
    actions = [
      "iam:AddClientIDToOpenIDConnectProvider",
      "iam:CreateOpenIDConnectProvider",
      "iam:DeleteOpenIDConnectProvider",
      "iam:GetOpenIDConnectProvider",
      "iam:ListOpenIDConnectProviders",
      "iam:RemoveClientIDFromOpenIDConnectProvider",
      "iam:TagOpenIDConnectProvider",
      "iam:UntagOpenIDConnectProvider",
      "iam:UpdateOpenIDConnectProviderThumbprint"
    ]
    resources = ["*"]
  }

  statement {
    sid       = "CreateRequiredServiceLinkedRoles"
    effect    = "Allow"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "iam:AWSServiceName"
      values = [
        "autoscaling.amazonaws.com",
        "budgets.amazonaws.com",
        "eks.amazonaws.com",
        "eks-nodegroup.amazonaws.com",
        "inspector2.amazonaws.com"
      ]
    }
  }
}

resource "aws_iam_role_policy" "terraform_apply" {
  name   = "${local.name_prefix}-terraform-apply"
  role   = aws_iam_role.terraform_apply.id
  policy = data.aws_iam_policy_document.terraform_apply.json
}

data "aws_iam_policy_document" "release" {
  statement {
    sid       = "GetECRAuthorizationToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "PushCoreImages"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:ListImages",
      "ecr:PutImage",
      "ecr:UploadLayerPart"
    ]
    resources = values(aws_ecr_repository.service)[*].arn
  }

  statement {
    sid       = "DiscoverCluster"
    effect    = "Allow"
    actions   = ["eks:DescribeCluster"]
    resources = [aws_eks_cluster.this.arn]
  }
}

resource "aws_iam_role_policy" "release" {
  name   = "${local.name_prefix}-release"
  role   = aws_iam_role.release.id
  policy = data.aws_iam_policy_document.release.json
}

resource "aws_eks_access_entry" "terraform_apply" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_iam_role.terraform_apply.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "terraform_apply" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_iam_role.terraform_apply.arn
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.terraform_apply]
}

resource "aws_eks_access_entry" "release" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_iam_role.release.arn
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "release" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_iam_role.release.arn
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"

  access_scope {
    type       = "namespace"
    namespaces = [local.namespace]
  }

  depends_on = [aws_eks_access_entry.release]
}
