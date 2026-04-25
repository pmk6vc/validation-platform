terraform {
  backend "gcs" {
    bucket = "zugzwang-381922-terraform-state-platform"
    prefix = "terraform/state"
  }
}
