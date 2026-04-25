terraform {
  backend "gcs" {
    bucket = "zugzwang-381922-terraform-state-sandbox"
    prefix = "terraform/state"
  }
}
